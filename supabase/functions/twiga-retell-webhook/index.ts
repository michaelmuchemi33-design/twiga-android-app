import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";

const cors={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"x-retell-signature, content-type","Access-Control-Allow-Methods":"POST, OPTIONS"};
const reply=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{...cors,"Content-Type":"application/json"}});
type Row=Record<string,any>;

function safeEqual(a:string,b:string) {
  if(a.length!==b.length) return false;
  let result=0;
  for(let i=0;i<a.length;i++) result |= a.charCodeAt(i)^b.charCodeAt(i);
  return result===0;
}
async function verifySignature(raw:string,key:string,header:string) {
  const match=header.match(/v=(\d+),d=([a-fA-F0-9]+)/);
  if(!match) return false;
  const timestamp=match[1],digest=match[2].toLowerCase();
  if(Math.abs(Date.now()-Number(timestamp))>5*60*1000) return false;
  const cryptoKey=await crypto.subtle.importKey("raw",new TextEncoder().encode(key),{name:"HMAC",hash:"SHA-256"},false,["sign"]);
  const signature=await crypto.subtle.sign("HMAC",cryptoKey,new TextEncoder().encode(raw+timestamp));
  const expected=Array.from(new Uint8Array(signature)).map(x=>x.toString(16).padStart(2,"0")).join("");
  return safeEqual(expected,digest);
}

Deno.serve(async(req:Request)=>{
  if(req.method==="OPTIONS") return new Response("ok",{headers:cors});
  if(req.method!=="POST") return reply({error:"POST only"},405);
  try {
    const raw=await req.text();
    const signature=req.headers.get("x-retell-signature") || "";
    const url=Deno.env.get("SUPABASE_URL") || "";
    const service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    if(!url || !service) return reply({error:"TWIGA webhook is not configured"},503);
    const admin=createClient(url,service);
    let payload:Row;
    try { payload=JSON.parse(raw); } catch { return reply({error:"Invalid JSON"},400); }
    const call:Row=payload.call || {};
    const callId=String(call.call_id || "");
    const agentId=String(call.agent_id || "");
    if(!callId || !agentId) return reply({error:"Missing call or agent identifier"},400);
    const {data:config,error:configError}=await admin.from("twiga_business_voice_configs")
      .select("business_id,retell_agent_id").eq("retell_agent_id",agentId).maybeSingle();
    if(configError || !config) return reply({error:"Unknown TWIGA Retell agent"},404);
    const {data:secret,error:secretError}=await admin.rpc("twiga_get_provider_secret",{p_business_id:config.business_id,p_provider:"retell"});
    if(secretError || !secret) return reply({error:"Retell webhook key unavailable"},503);
    if(!(await verifySignature(raw,String(secret),signature))) return reply({error:"Invalid Retell webhook signature"},401);

    const event=String(payload.event || "");
    const start=call.start_timestamp ? new Date(Number(call.start_timestamp)).toISOString() : null;
    const end=call.end_timestamp ? new Date(Number(call.end_timestamp)).toISOString() : null;
    const duration=start && end ? Math.max(0,Math.round((new Date(end).getTime()-new Date(start).getTime())/1000)) : 0;
    const analysis:Row=call.call_analysis || {};
    const custom:Row=analysis.custom_analysis_data || {};
    const recordingUrl=String(call.recording_url || "");
    const metadata:Row={
      twiga_agent_id:agentId,
      retell_call_type:call.call_type || null,
      disconnection_reason:call.disconnection_reason || null,
      custom_analysis_data:custom,
      recording_url:recordingUrl || null,
      recording_url_expires_at:recordingUrl ? new Date(Date.now()+24*60*60*1000).toISOString() : null,
      recording_stored_in_retell:true,
      recording_copied_to_r2:false
    };
    const summary=String(analysis.call_summary || analysis.call_summary_text || "");
    const transcript=String(call.transcript || "");
    const status=event==="call_started" ? "in_progress" : (event==="call_ended" || event==="call_analyzed" ? "completed" : "in_progress");
    const callRecord={
      business_id:config.business_id,provider:"retell",provider_call_id:callId,
      channel:call.call_type==="web_call" ? "web" : "phone",
      direction:String(call.direction || "inbound"),
      from_number:call.from_number || null,to_number:call.to_number || null,
      status,started_at:start,ended_at:end,duration_seconds:duration,
      transcript:transcript || null,summary:summary || null,
      structured_data:custom,metadata,created_by:null
    };
    const {error:recordError}=await admin.from("twiga_call_records")
      .upsert(callRecord,{onConflict:"provider,provider_call_id"});
    if(recordError) {
      console.error("Retell call record save failed",recordError.message);
      return reply({error:"Could not save Retell call record"},500);
    }

    if(event==="call_analyzed") {
      const needsFollowup=custom.follow_up_required===true;
      const appointment=String(custom.appointment_request || "").trim();
      const order=String(custom.order_request || "").trim();
      const action=String(custom.follow_up_action || "").trim();
      const followTime=String(custom.follow_up_time || "").trim();
      const enquiryType=String(custom.enquiry_type || "").trim();
      const caller=String(custom.customer_name || call.from_number || "Caller").trim();
      const phone=String(custom.customer_phone || call.from_number || "").trim();
      const {data:biz}=await admin.from("businesses").select("created_by").eq("id",config.business_id).maybeSingle();

      // Save a concrete appointment as a pending request only when the caller agreed an exact ISO time.
      const appointmentStartRaw=String(custom.appointment_start_iso || "").trim();
      const appointmentDuration=Math.min(180,Math.max(15,Number(custom.appointment_duration_minutes || 30)));
      const appointmentService=String(custom.appointment_service || "").trim();
      if(appointment && appointmentStartRaw && biz?.created_by && /(?:Z|[+-]\d{2}:\d{2})$/.test(appointmentStartRaw)) {
        const appointmentStart=new Date(appointmentStartRaw);
        const now=Date.now();
        if(Number.isFinite(appointmentStart.getTime()) &&
           appointmentStart.getTime()>now-5*60*1000 &&
           appointmentStart.getTime()<now+366*86400000) {
          const appointmentMarker="Retell call ID: "+callId;
          const {data:existingAppointment}=await admin.from("twiga_appointments").select("id")
            .eq("business_id",config.business_id).ilike("notes","%"+appointmentMarker+"%").limit(1).maybeSingle();
          if(!existingAppointment) {
            const appointmentNotes=[
              "PENDING REQUEST — business must confirm with customer before treating as booked.",
              appointmentService ? "Service: "+appointmentService : "",
              "Requested duration minutes: "+appointmentDuration,
              "Request details: "+appointment,
              "Call summary: "+(summary || ""),
              appointmentMarker
            ].filter(Boolean).join("\n");
            const {error:appointmentError}=await admin.from("twiga_appointments").insert({
              business_id:config.business_id,created_by:biz.created_by,
              customer_name:caller.slice(0,160),phone:phone.slice(0,60) || null,
              scheduled_at:appointmentStart.toISOString(),notes:appointmentNotes,status:"requested"
            });
            if(appointmentError) console.error("Retell appointment request save failed",appointmentError.message);
          }
        }
      }

      if(biz?.created_by && custom.customer_name && phone) {
        const {data:existingCustomer}=await admin.from("twiga_customers")
          .select("id").eq("business_id",config.business_id).eq("phone",phone).limit(1).maybeSingle();
        if(!existingCustomer) {
          const {error:customerError}=await admin.from("twiga_customers").insert({
            business_id:config.business_id,created_by:biz.created_by,
            name:String(custom.customer_name).slice(0,160),phone:phone.slice(0,60),
            notes:"Captured from Retell conversation. Call ID: "+callId
          });
          if(customerError) console.error("Retell customer capture failed",customerError.message);
        }
      }

      const tasks:string[]=[];
      if(enquiryType && !["other","general","none",""].includes(enquiryType.toLowerCase()) && (summary || transcript)) {
        tasks.push("New enquiry: "+enquiryType+" — "+caller);
      }
      if(needsFollowup || action) tasks.push(action || "Follow up with "+caller+" after the phone enquiry");
      if(appointment) tasks.push("Confirm appointment request: "+appointment);
      if(order) tasks.push("Review order request: "+order);
      if(tasks.length && biz?.created_by) {
        for(const task of Array.from(new Set(tasks)).slice(0,4)) {
          const marker="Retell call ID: "+callId;
          const desc=[
            "Automatically created from a completed Retell call.",
            "Customer: "+caller,
            "Callback number: "+(phone || "Not supplied"),
            "Enquiry type: "+(enquiryType || "Not classified"),
            "Requested follow-up time: "+(followTime || "Not specified"),
            "Details: "+task,
            "Call summary: "+(summary || "See call transcript in TWIGA call history."),
            marker
          ].join("\n");
          const {data:existing}=await admin.from("twiga_tasks").select("id")
            .eq("business_id",config.business_id)
            .ilike("description","%"+marker+"%")
            .ilike("title","%"+task.slice(0,40)+"%")
            .limit(1).maybeSingle();
          if(!existing) {
            const {error:taskError}=await admin.from("twiga_tasks").insert({
              business_id:config.business_id,created_by:biz.created_by,
              title:task.slice(0,180),description:desc,status:"open"
            });
            if(taskError) console.error("Retell follow-up task creation failed",taskError.message);
          }
        }
      }
    }
    return new Response(null,{status:204,headers:cors});
  } catch(error) {
    console.error("twiga-retell-webhook",error);
    return reply({error:"Webhook processing failed"},500);
  }
});