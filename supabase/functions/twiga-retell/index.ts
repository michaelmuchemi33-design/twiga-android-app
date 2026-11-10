import { createClient } from "https://esm.sh/@supabase/supabase-js@2.49.1";
const cors = {"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, apikey, content-type","Access-Control-Allow-Methods":"POST, OPTIONS"};
const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), {status, headers:{...cors,"Content-Type":"application/json"}});
type Row = Record<string, any>;
const RETELL = "https://api.retellai.com";

async function retell(key:string,path:string,method="GET",body?:unknown) {
  const response = await fetch(RETELL + path,{method,headers:{Authorization:"Bearer "+key,"Content-Type":"application/json"},...(body===undefined?{}:{body:JSON.stringify(body)})});
  const raw = await response.text();
  let data:Row = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = {message:raw.slice(0,800)}; }
  return response.ok ? {ok:true,status:response.status,data,message:""} : {ok:false,status:response.status,data,message:String(data.message || data.error || data.detail || ("Retell HTTP "+response.status))};
}

function buildPrompt(b:Row,p:Row|null,items:Row[],inbound:string,outbound:string) {
  const productText = items.slice(0,80).map(x => "- "+x.name+": "+(x.description || "No description")+", price "+(x.currency || "KES")+" "+x.price+", stock "+(x.stock_quantity ?? "not stated")).join("\n");
  const facts = [
    "Business: "+b.name, "Business type: "+(b.business_type || ""),
    "Overview: "+(p?.business_summary || ""), "What they sell: "+(p?.what_they_sell || ""),
    "What they do: "+(p?.what_they_do || ""), "Products/services: "+(p?.products_and_services || ""),
    "Target customers: "+(p?.target_customers || ""), "Unique selling points: "+(p?.unique_selling_points || ""),
    "Service area: "+(p?.service_area || ""), "Opening hours: "+(p?.opening_hours || ""),
    "Payments/delivery: "+(p?.delivery_and_payment || ""), "Returns/refunds: "+(p?.returns_and_refunds || ""),
    "FAQs: "+(p?.frequently_asked_questions || ""), "Brand voice: "+(p?.brand_voice || "Warm, calm, helpful and professional"),
    "Business-specific instructions: "+(p?.assistant_instructions || ""),
    "Products in THIS workspace only:\n"+(productText || "No products entered yet.")
  ].join("\n");
  return [
    "You are the professional AI receptionist and business helper for "+b.name+". Serve only this business workspace. Never mix another business's data, customers, products, appointments or records.",
    "LANGUAGES: Always support British English (en-GB) and Kiswahili (sw-KE). Detect the language used and reply in that language. Natural English/Kiswahili code-switching is welcome. Calm, warm, middle-aged British-English voice, with natural Kiswahili pronunciation where supported. Keep replies concise.",
    "ROLE: Handle enquiries, explain real products/services and prices, collect customer details when appropriate, capture order and appointment requests, answer FAQs, and prepare clear follow-up actions.",
    "ACCURACY: Use only supplied facts. Never invent prices, stock, opening hours, discounts, booking availability, delivery terms, payment status, order status or policies. Say you will ask the team when information is missing.",
    "ACTION SAFETY: Never claim an appointment, order or payment is confirmed. For a clear appointment request, repeat the date, time, service and customer details, then capture them as a pending TWIGA appointment request. Explain that the business will confirm it. TWIGA saves it in this business's appointments and the owner can sync it to Google Calendar. Do not tell the caller that a calendar event or final booking is confirmed.",
    "FOLLOW-UP: Identify whether human follow-up is needed, collect a callback number if volunteered, ask for a suitable time, and summarize the exact requested action. Respect requests not to be contacted again.",
    "INBOUND RULES: "+(inbound || "Greet the customer, ask how to help, and guide the conversation toward a clear enquiry, appointment request, order request or message."),
    "OUTBOUND RULES: "+(outbound || "For an authorised outgoing call, identify yourself and the business, explain the reason, ask whether this is a convenient time, and stop politely if the person declines."),
    "BUSINESS KNOWLEDGE (at agent setup):\n"+facts,
    "CURRENT BUSINESS CONTEXT (refreshed from this business's own TWIGA data at the start of every call; this overrides older static details): {{twiga_business_context}}",
    "PRIVACY: Only collect information needed for the request. Never ask for passwords, PINs, card numbers or API credentials. Never disclose another customer's information. Follow the business's recording notice and consent process."
  ].join("\n\n");
}

function paidAccess(sub:Row|null,biz:Row|null) {
  const now=Date.now();
  if(sub?.status==="trialing" && sub.trial_ends_at) return new Date(sub.trial_ends_at).getTime()+3*86400000>now;
  if(sub && ["active","past_due"].includes(String(sub.status))) return !!sub.grace_period_ends_at && new Date(sub.grace_period_ends_at).getTime()>now;
  if(!sub && biz) return ["TRIAL","ACTIVE"].includes(String(biz.status)) && new Date(String(biz.paid_through)+"T23:59:59+03:00").getTime()+3*86400000>now;
  return false;
}

Deno.serve(async (req:Request) => {
  if(req.method==="OPTIONS") return new Response("ok",{headers:cors});
  if(req.method!=="POST") return reply({error:"POST only"},405);
  try {
    const auth=req.headers.get("Authorization") || "";
    if(!auth.startsWith("Bearer ")) return reply({error:"Sign in required"},401);
    const url=Deno.env.get("SUPABASE_URL") || "";
    const anon=Deno.env.get("SUPABASE_ANON_KEY") || "";
    const service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") || "";
    if(!url || !anon || !service) return reply({error:"TWIGA server is not configured"},503);
    const userClient=createClient(url,anon,{global:{headers:{Authorization:auth}}});
    const {data:{user},error:authError}=await userClient.auth.getUser();
    if(authError || !user) return reply({error:"Your sign-in has expired. Sign in again."},401);
    const admin=createClient(url,service);
    const body=await req.json().catch(()=>({}));
    const action=String(body.action || "get_config");
    const businessId=String(body.businessId || "");
    if(!businessId) return reply({error:"Choose a business first."},400);

    const [memberResult,adminResult]=await Promise.all([
      admin.from("business_members").select("role").eq("business_id",businessId).eq("user_id",user.id).maybeSingle(),
      admin.from("twiga_platform_admins").select("user_id").eq("user_id",user.id).maybeSingle()
    ]);
    const membership=memberResult.data, isPlatformAdmin=!!adminResult.data;
    if(!membership && !isPlatformAdmin) return reply({error:"You are not authorised for this business."},403);
    const canManage=isPlatformAdmin || ["owner","admin"].includes(String(membership?.role || ""));
    if(["save_key","save_settings","provision_agent"].includes(action) && !canManage)
      return reply({error:"Only the business owner, business admin or TWIGA platform admin can change AI settings."},403);

    if(action==="get_config") {
      const [configResult,keyResult,recordingResult,lineResult]=await Promise.all([
        admin.from("twiga_business_voice_configs").select("*").eq("business_id",businessId).maybeSingle(),
        admin.from("twiga_provider_secret_refs").select("secret_last4,updated_at").eq("business_id",businessId).eq("provider","retell").maybeSingle(),
        admin.from("twiga_business_settings").select("call_recording_enabled,recording_notice").eq("business_id",businessId).maybeSingle(),
        admin.from("ai_lines").select("number").eq("business_id",businessId).maybeSingle()
      ]);
      return reply({
        config:configResult.data || {business_id:businessId,provider:"vapi",retell_voice_id:"retell-Willa",retell_voice_model:null,retell_model:"gpt-6-luna",retell_language_codes:["en-GB","sw-KE"],retell_ambient_sound:"call-center",retell_ambient_sound_volume:0.12},
        keyConfigured:!!keyResult.data,keyLast4:keyResult.data?.secret_last4 || "",keyUpdatedAt:keyResult.data?.updated_at || null,
        recordingEnabled:recordingResult.data?.call_recording_enabled===true,
        recordingNotice:recordingResult.data?.recording_notice || "This call may be recorded to help you.",
        aiBridgeNumber:lineResult.data?.number || "",
        canManage,isPlatformAdmin
      });
    }

    if(action==="list_phone_numbers") {
      const {data:secret,error:secretError}=await admin.rpc("twiga_get_provider_secret",{p_business_id:businessId,p_provider:"retell"});
      if(secretError || !secret) return reply({error:"Connect a valid Retell key for this business first."},400);
      const result=await retell(String(secret),"/v2/list-phone-numbers","GET");
      if(!result.ok) return reply({error:"Could not list Retell phone numbers: "+result.message},502);
      return reply({phoneNumbers:result.data.phone_numbers || result.data.items || [],hasMore:!!result.data.has_more});
    }

    if(action==="save_key") {
      const apiKey=String(body.apiKey || "").trim();
      if(apiKey.length<20 || apiKey.length>500) return reply({error:"Enter a valid Retell API key."},400);
      const check=await retell(apiKey,"/get-concurrency");
      if(!check.ok) return reply({error:check.status===401?"Retell rejected this API key. Check it and try again.":check.message},400);
      const {data:last4,error:vaultError}=await admin.rpc("twiga_set_provider_secret",{p_business_id:businessId,p_provider:"retell",p_secret:apiKey,p_updated_by:user.id});
      if(vaultError) { console.error("Retell Vault save failed",vaultError.message); return reply({error:"The Retell key validated but could not be stored securely."},500); }
      return reply({saved:true,keyLast4:last4,message:"Retell key validated and stored in Supabase Vault. The key is never returned to the app."});
    }

    if(action==="save_settings") {
      const voiceId=String(body.voiceId || "retell-Willa");
      const voiceModel=voiceId==="retell-Willa" ? null : String(body.voiceModel || "eleven_turbo_v2_5");
      const model=String(body.model || "gpt-6-luna");
      if(!["retell-Willa","11labs-Willa","cartesia-Willa"].includes(voiceId)) return reply({error:"Unsupported Willa voice option."},400);
      if(voiceModel!==null && !["eleven_turbo_v2","eleven_flash_v2","eleven_turbo_v2_5","eleven_flash_v2_5","eleven_multilingual_v2","eleven_v3","sonic-3","sonic-3-latest","sonic-3.5","tts-1","gpt-4o-mini-tts","speech-02-turbo","speech-2.8-turbo","s1","s2-pro","s2.1-pro"].includes(voiceModel)) return reply({error:"Unsupported voice model."},400);
      if(!["gpt-6-luna","gpt-5.6-luna"].includes(model)) return reply({error:"Unsupported Retell model."},400);
      const ambient=Math.min(0.3,Math.max(0,Number(body.ambientVolume ?? 0.12)));
      const recordingEnabled=body.recordingEnabled===true;
      const recordingNotice=String(body.recordingNotice || "This call may be recorded to help us serve you.").trim().slice(0,500);
      const config={
        provider:body.provider==="retell"?"retell":"vapi",
        retell_voice_id:voiceId,retell_voice_model:voiceModel,retell_model:model,
        retell_language_codes:["en-GB","sw-KE"],retell_ambient_sound:"call-center",
        retell_ambient_sound_volume:ambient,inbound_prompt:String(body.inboundPrompt || "").slice(0,12000),
        outbound_prompt:String(body.outboundPrompt || "").slice(0,12000),
        updated_by:user.id,updated_at:new Date().toISOString()
      };
      const {data:existing,error:existingError}=await admin.from("twiga_business_voice_configs")
        .select("business_id").eq("business_id",businessId).maybeSingle();
      if(existingError) throw existingError;
      const savedResult=existing
        ? await admin.from("twiga_business_voice_configs").update(config).eq("business_id",businessId)
            .select("business_id,provider,retell_agent_id,retell_llm_id,retell_voice_id,retell_voice_model,retell_model,retell_language_codes,retell_ambient_sound,retell_ambient_sound_volume,inbound_prompt,outbound_prompt,last_provisioned_at,updated_at").single()
        : await admin.from("twiga_business_voice_configs").insert({...config,business_id:businessId})
            .select("business_id,provider,retell_agent_id,retell_llm_id,retell_voice_id,retell_voice_model,retell_model,retell_language_codes,retell_ambient_sound,retell_ambient_sound_volume,inbound_prompt,outbound_prompt,last_provisioned_at,updated_at").single();
      if(savedResult.error) throw savedResult.error;
      const {error:recordingSaveError}=await admin.from("twiga_business_settings").upsert({
        business_id:businessId,call_recording_enabled:recordingEnabled,recording_notice:recordingNotice,
        updated_at:new Date().toISOString()
      },{onConflict:"business_id"});
      if(recordingSaveError) throw recordingSaveError;
      if(Object.prototype.hasOwnProperty.call(body,"bridgeNumber")) {
        const bridgeNumber=String(body.bridgeNumber || "").trim();
        if(bridgeNumber && !/^\\+[1-9][0-9]{6,14}$/.test(bridgeNumber))
          return reply({error:"The AI bridge number must be in international E.164 format, for example +2547XXXXXXXX."},400);
        if(bridgeNumber) {
          const {data:claimed,error:claimError}=await admin.from("ai_lines").select("business_id").eq("number",bridgeNumber).maybeSingle();
          if(claimError) throw claimError;
          if(claimed && claimed.business_id!==businessId)
            return reply({error:"That AI number is already assigned to another business."},409);
          const {error:deleteError}=await admin.from("ai_lines").delete().eq("business_id",businessId);
          if(deleteError) throw deleteError;
          const {error:lineError}=await admin.from("ai_lines").insert({number:bridgeNumber,business_id:businessId});
          if(lineError) throw lineError;
        } else {
          const {error:deleteError}=await admin.from("ai_lines").delete().eq("business_id",businessId);
          if(deleteError) throw deleteError;
        }
      }
      let recordingAppliedToAgent=false;
      if(config.provider==="retell" && savedResult.data?.retell_agent_id) {
        const {data:retellSecret}=await admin.rpc("twiga_get_provider_secret",{p_business_id:businessId,p_provider:"retell"});
        if(!retellSecret) return reply({error:"Settings were saved, but the existing Retell agent could not be updated because its business API key is missing."},409);
        const agentUpdate=await retell(String(retellSecret),"/update-agent/"+encodeURIComponent(String(savedResult.data.retell_agent_id)),"PATCH",{
          data_storage_setting:recordingEnabled ? "everything" : "basic_attributes_only",
          opt_in_signed_url:recordingEnabled,
          signed_url_expiration_ms:86400000
        });
        if(!agentUpdate.ok) return reply({error:"Settings were saved, but Retell could not apply the recording preference to the current agent: "+agentUpdate.message},502);
        const published=await retell(String(retellSecret),"/publish-agent","POST",{agent_id:String(savedResult.data.retell_agent_id)});
        if(!published.ok) return reply({error:"Recording preference was updated in Retell's draft but could not be published. Re-open AI settings and build the assistant again."},502);
        recordingAppliedToAgent=true;
      }
      return reply({saved:true,config:savedResult.data,recordingEnabled,recordingNotice,recordingAppliedToAgent});
    }

    if(action==="link_existing_agent") {
      const agentId=String(body.agentId || "").trim();
      if(!agentId || agentId.length>255) return reply({error:"Enter the existing Retell agent ID."},400);
      const {data:secret,error:secretError}=await admin.rpc("twiga_get_provider_secret",{p_business_id:businessId,p_provider:"retell"});
      if(secretError || !secret) return reply({error:"Connect and validate this business's Retell API key first."},400);
      const found=await retell(String(secret),"/get-agent/"+encodeURIComponent(agentId));
      if(!found.ok) return reply({error:"Retell could not find that agent using this API key: "+found.message},404);
      const agent=found.data || {};
      const engine=agent.response_engine || {};
      const llmId=String(engine.llm_id || agent.llm_id || "");
      const {data:existing}=await admin.from("twiga_business_voice_configs").select("business_id").eq("business_id",businessId).maybeSingle();
      const update={provider:"retell",retell_agent_id:agentId,retell_llm_id:llmId || null,retell_voice_id:String(agent.voice_id || "retell-Willa"),retell_voice_model:agent.voice_model || null,updated_by:user.id,updated_at:new Date().toISOString()};
      const saved=existing
        ? await admin.from("twiga_business_voice_configs").update(update).eq("business_id",businessId).select("business_id,provider,retell_agent_id,retell_llm_id,retell_voice_id,retell_voice_model,retell_model,retell_language_codes,inbound_prompt,outbound_prompt").single()
        : await admin.from("twiga_business_voice_configs").insert({...update,business_id:businessId}).select("business_id,provider,retell_agent_id,retell_llm_id,retell_voice_id,retell_voice_model,retell_model,retell_language_codes,inbound_prompt,outbound_prompt").single();
      if(saved.error) throw saved.error;
      return reply({linked:true,config:saved.data,agentName:agent.agent_name || null,agentId});
    }

    if(action==="provision_agent") {
      const {data:secret,error:secretError}=await admin.rpc("twiga_get_provider_secret",{p_business_id:businessId,p_provider:"retell"});
      if(secretError || !secret) return reply({error:"Connect a valid Retell key for this business first."},400);
      const [bizResult,profileResult,productsResult,configResult,recordingResult]=await Promise.all([
        admin.from("businesses").select("id,name,business_type,created_by").eq("id",businessId).single(),
        admin.from("twiga_business_profiles").select("*").eq("business_id",businessId).maybeSingle(),
        admin.from("twiga_products").select("name,description,price,currency,stock_quantity").eq("business_id",businessId).eq("is_active",true).order("name").limit(80),
        admin.from("twiga_business_voice_configs").select("*").eq("business_id",businessId).maybeSingle(),
        admin.from("twiga_business_settings").select("call_recording_enabled,recording_notice").eq("business_id",businessId).maybeSingle()
      ]);
      const biz=bizResult.data,config=configResult.data;
      if(bizResult.error || !biz) return reply({error:"Business not found."},404);
      if(productsResult.error) return reply({error:"Could not load this business's product catalogue."},500);
      if(!config) return reply({error:"Save the voice settings before provisioning."},400);
      const check=await retell(String(secret),"/get-concurrency");
      if(!check.ok) return reply({error:"Retell key no longer works. Replace it in AI settings."},400);
      const prompt=buildPrompt(biz,profileResult.data,productsResult.data || [],config.inbound_prompt || "",config.outbound_prompt || "") +
        "\\n\\nCALL RECORDING POLICY: The current recording preference and exact notice arrive in this call's fresh TWIGA business context. If recordingEnabled is true, announce recordingNotice immediately and request consent before discussing the enquiry. If recordingEnabled is false, do not claim the call is recorded. Never invent consent.";
      const requested=String(config.retell_model || "gpt-6-luna");
      const llmBody={
        model:requested,start_speaker:"agent",
        begin_message:"Hello, thank you for contacting "+biz.name+". How may I help you today?",
        general_prompt:prompt,model_temperature:0.1,begin_after_user_silence_ms:1200
      };
      const actual=requested;
      const existingLlmId=String(config.retell_llm_id || "");
      const llm=existingLlmId
        ? await retell(String(secret),"/update-retell-llm/"+encodeURIComponent(existingLlmId),"PATCH",llmBody)
        : await retell(String(secret),"/create-retell-llm","POST",llmBody);
      const llmId=existingLlmId || String(llm.data.llm_id || "");
      if(!llm.ok || !llmId) {
        return reply({error:"Retell could not save the selected language model ("+requested+"): "+(llm.message || "No LLM id returned")+". Choose another model only if you approve its different price."},502);
      }
      const agentBody={
        agent_name:("TWIGA - "+biz.name).slice(0,120),
        response_engine:{type:"retell-llm",llm_id:llmId},
        voice_id:config.retell_voice_id || "retell-Willa",
        voice_model:config.retell_voice_id === "retell-Willa" ? null : (config.retell_voice_model || null),
        language:["en-GB","sw-KE"],voice_emotion:"calm",
        ambient_sound:"call-center",ambient_sound_volume:Number(config.retell_ambient_sound_volume ?? 0.12),
        responsiveness:0.92,interruption_sensitivity:0.8,
        enable_backchannel:true,backchannel_frequency:0.45,
        webhook_url:url+"/functions/v1/twiga-retell-webhook",
        webhook_events:["call_started","call_ended","call_analyzed"],webhook_timeout_ms:10000,
        data_storage_setting:recordingResult.data?.call_recording_enabled===true ? "everything" : "basic_attributes_only",
        opt_in_signed_url:recordingResult.data?.call_recording_enabled===true,
        data_storage_retention_days:30,
        signed_url_expiration_ms:86400000,max_call_duration_ms:600000,end_call_after_silence_ms:30000,
        begin_message_delay_ms:350,
        post_call_analysis_data:[
          {type:"string",name:"customer_name",description:"Customer name if volunteered."},
          {type:"string",name:"customer_phone",description:"Callback number supplied or available in call metadata."},
          {type:"string",name:"enquiry_type",description:"Enquiry, appointment request, order request, complaint, callback request or other."},
          {type:"boolean",name:"follow_up_required",description:"Whether a human follow-up is needed."},
          {type:"string",name:"follow_up_action",description:"Specific next action requested by the caller."},
          {type:"string",name:"follow_up_time",description:"Requested callback/follow-up time, if supplied."},
          {type:"string",name:"appointment_request",description:"Full requested appointment summary: service, date and time; pending confirmation, not confirmed."},
          {type:"string",name:"appointment_start_iso",description:"If the caller clearly agrees on an exact date and time, provide ISO 8601 with Africa/Nairobi offset +03:00, such as 2026-11-24T14:00:00+03:00. Leave empty if unclear or incomplete; never guess."},
          {type:"number",name:"appointment_duration_minutes",description:"Requested appointment length in minutes if the caller or business profile specifies it; otherwise leave empty."},
          {type:"string",name:"appointment_service",description:"Specific service or appointment reason requested."},
          {type:"string",name:"order_request",description:"Products, quantities and preferences; this is not a confirmed order."}
        ]
      };
      const existingAgentId=String(config.retell_agent_id || "");
      const agent=existingAgentId
        ? await retell(String(secret),"/update-agent/"+encodeURIComponent(existingAgentId),"PATCH",agentBody)
        : await retell(String(secret),"/create-agent","POST",agentBody);
      const agentId=existingAgentId || String(agent.data.agent_id || "");
      if(!agent.ok || !agentId) return reply({error:"Retell could not save the voice agent: "+(agent.message || "No agent id returned")},502);
      const published=await retell(String(secret),"/publish-agent","POST",{agent_id:agentId});
      if(!published.ok) return reply({error:"Agent draft was saved but Retell did not publish it. Review the draft in Retell before retrying.",agentCreated:!existingAgentId,agentId},502);
      const {data:saved,error:saveError}=await admin.from("twiga_business_voice_configs").update({
        provider:"retell",retell_agent_id:agentId,retell_llm_id:llmId,
        retell_model:actual,last_provisioned_at:new Date().toISOString(),updated_at:new Date().toISOString()
      }).eq("business_id",businessId)
        .select("business_id,provider,retell_agent_id,retell_llm_id,retell_voice_id,retell_voice_model,retell_model,retell_language_codes,retell_ambient_sound,retell_ambient_sound_volume,last_provisioned_at").single();
      if(saveError) throw saveError;
      return reply({
        provisioned:true,published:true,updated:!!existingAgentId,config:saved,
        modelNotice:"Retell was configured with the exact model you selected; TWIGA does not silently switch to a different price tier.",
        message:existingAgentId
          ?"The existing business-specific Retell receptionist was updated and published. Vapi remains available."
          :"The business-specific Retell receptionist was created and published. Vapi remains available."
      });
    }

    if(action==="create_web_call") {
      const [configResult,subsResult,businessResult]=await Promise.all([
        admin.from("twiga_business_voice_configs").select("provider,retell_agent_id").eq("business_id",businessId).maybeSingle(),
        admin.from("twiga_subscriptions").select("status,trial_ends_at,grace_period_ends_at").eq("business_id",businessId).order("created_at",{ascending:false}).limit(1),
        admin.from("businesses").select("name,business_type,status,paid_through").eq("id",businessId).single()
      ]);
      if(!paidAccess(subsResult.data?.[0] || null,businessResult.data || null)) return reply({error:"TWIGA AI voice is paused because the payment grace period has ended. Normal phone calls and WhatsApp are unaffected."},402);
      const config=configResult.data;
      if(config?.provider!=="retell" || !config.retell_agent_id) return reply({error:"Retell is not selected and published for this business."},409);
      const {data:secret}=await admin.rpc("twiga_get_provider_secret",{p_business_id:businessId,p_provider:"retell"});
      if(!secret) return reply({error:"Retell credentials are missing for this business."},503);
      const [profileResult,productsResult,recordingResult]=await Promise.all([
        admin.from("twiga_business_profiles").select("business_summary,what_they_sell,what_they_do,products_and_services,target_customers,unique_selling_points,service_area,opening_hours,delivery_and_payment,returns_and_refunds,frequently_asked_questions,brand_voice,reply_language,assistant_instructions").eq("business_id",businessId).maybeSingle(),
        admin.from("twiga_products").select("name,description,category,price,currency,unit,stock_quantity").eq("business_id",businessId).eq("is_active",true).order("name").limit(80),
        admin.from("twiga_business_settings").select("call_recording_enabled,recording_notice").eq("business_id",businessId).maybeSingle()
      ]);
      const recordingEnabled=recordingResult.data?.call_recording_enabled===true;
      const recordingNotice=String(recordingResult.data?.recording_notice || "This call may be recorded to help us serve you.");
      const liveContext={
        businessName:businessResult.data?.name || "the business",
        businessType:businessResult.data?.business_type || "business",
        profile:profileResult.data || {},
        activeProducts:productsResult.data || [],
        recordingEnabled,
        recordingNotice
      };
      const dynamic={
        business_name:String(businessResult.data?.name || "the business"),
        business_type:String(businessResult.data?.business_type || "business"),
        twiga_business_context:JSON.stringify(liveContext).slice(0,24000),
        twiga_business_id:businessId
      };
      const webCall=await retell(String(secret),"/v2/create-web-call","POST",{
        agent_id:config.retell_agent_id,
        retell_llm_dynamic_variables:dynamic,
        metadata:{twiga_business_id:businessId,twiga_user_id:user.id,source:"twiga_android_app"}
      });
      if(!webCall.ok || !webCall.data.access_token) return reply({error:webCall.message || "Retell could not start the web call."},502);
      return reply({accessToken:webCall.data.access_token,callId:webCall.data.call_id,provider:"retell",expiresInSeconds:30});
    }
    return reply({error:"Unknown Retell action."},400);
  } catch(error) {
    console.error("twiga-retell",error);
    return reply({error:"TWIGA could not complete the Retell request."},500);
  }
});