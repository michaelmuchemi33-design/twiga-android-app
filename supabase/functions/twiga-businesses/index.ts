import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
const cors={"Access-Control-Allow-Origin":"*","Access-Control-Allow-Headers":"authorization, x-client-info, apikey, content-type","Access-Control-Allow-Methods":"POST, OPTIONS","Content-Type":"application/json"};
const json=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:cors});
Deno.serve(async(req:Request)=>{
 if(req.method==="OPTIONS") return new Response("ok",{headers:cors});
 if(req.method!=="POST") return json({error:"POST only"},405);
 const auth=req.headers.get("Authorization")||""; if(!auth.startsWith("Bearer ")) return json({error:"Sign in required."},401);
 const url=Deno.env.get("SUPABASE_URL")||"",service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")||"";
 if(!url||!service) return json({error:"TWIGA server is not configured."},503);
 const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
 const {data:authData,error:authError}=await admin.auth.getUser(auth.slice(7).trim());
 if(authError||!authData.user) return json({error:"Session expired. Sign in again."},401);
 const user=authData.user;
 try{
  const body=await req.json().catch(()=>({})); const action=String(body.action||"list");
  if(action==="list"){
   const {data,error}=await admin.from("business_members").select("business_id,role,businesses(id,name,business_type,status,paid_through,created_at)").eq("user_id",user.id);
   if(error) throw error;
   return json({businesses:(data||[]).map((row:any)=>({id:row.business_id,role:row.role,...(Array.isArray(row.businesses)?row.businesses[0]:row.businesses||{})}))});
  }
  if(action==="create"){
   const name=String(body.name||"").trim().slice(0,120), businessType=String(body.businessType||"general").trim().slice(0,80)||"general";
   const p=body.profile&&typeof body.profile==="object"?body.profile:{};
   if(name.length<2) return json({error:"Add your business name first."},400);
   const {data:business,error:be}=await admin.from("businesses").insert({name,business_type:businessType,created_by:user.id}).select("id,name,business_type,status,paid_through,created_at").single();
   if(be||!business) throw be||new Error("Business could not be created.");
   try{
    const businessId=business.id;
    const {data:existingMember,error:memberReadError}=await admin.from("business_members").select("business_id").eq("business_id",businessId).eq("user_id",user.id).maybeSingle();
    if(memberReadError) throw memberReadError;
    if(!existingMember){const {error:memberInsertError}=await admin.from("business_members").insert({business_id:businessId,user_id:user.id,role:"owner"});if(memberInsertError)throw memberInsertError;}
    const profile={
     business_id:businessId,business_summary:String(p.business_summary||"").slice(0,12000),what_they_sell:String(p.what_they_sell||"").slice(0,12000),what_they_do:String(p.what_they_do||"").slice(0,12000),products_and_services:String(p.products_and_services||"").slice(0,16000),target_customers:String(p.target_customers||"").slice(0,8000),unique_selling_points:String(p.unique_selling_points||"").slice(0,8000),service_area:String(p.service_area||"").slice(0,4000),opening_hours:String(p.opening_hours||"").slice(0,4000),delivery_and_payment:String(p.delivery_and_payment||"").slice(0,8000),returns_and_refunds:String(p.returns_and_refunds||"").slice(0,8000),frequently_asked_questions:String(p.frequently_asked_questions||"").slice(0,12000),brand_voice:String(p.brand_voice||"Calm, warm, helpful and professional").slice(0,500),reply_language:String(p.reply_language||"match_customer").slice(0,100),assistant_instructions:String(p.assistant_instructions||"").slice(0,16000),updated_by:user.id
    };
    const {error:pe}=await admin.from("twiga_business_profiles").upsert(profile,{onConflict:"business_id"}); if(pe) throw pe;
    const {error:se}=await admin.from("twiga_business_settings").upsert({business_id:businessId,timezone:String(p.timezone||"Africa/Nairobi"),language_mode:"auto",business_hours:p.business_hours&&typeof p.business_hours==="object"?p.business_hours:{},followups_enabled:true},{onConflict:"business_id"}); if(se) throw se;
    const {error:ve}=await admin.from("twiga_business_voice_configs").upsert({business_id:businessId,provider:"sautikit",retell_voice_id:"",retell_model:"",retell_language_codes:["en-KE","sw-KE"],sautikit_model_id:"sautikit-flash",sautikit_voice_id:"Woman",inbound_prompt:"Greet callers warmly, identify what they need, and answer using only this business's verified information. Capture appointment or order requests for staff confirmation.",outbound_prompt:"Only call people when the business has authorised the follow-up. Identify the business, explain the reason, and respect requests not to be contacted.",updated_by:user.id},{onConflict:"business_id"}); if(ve) throw ve;
    const {error:we}=await admin.from("twiga_business_workspace_data").upsert({business_id:businessId,updated_by:user.id,workspace_data:{}},{onConflict:"business_id"}); if(we) throw we;
   }catch(e){await admin.from("businesses").delete().eq("id",business.id);throw e;}
   return json({business:{...business,role:"owner"},created:true});
  }
  if(action==="rename"){
   const businessId=String(body.businessId||""),name=String(body.name||"").trim().slice(0,120);
   if(!businessId||name.length<2) return json({error:"Business and valid name are required."},400);
   const {data:member}=await admin.from("business_members").select("role").eq("business_id",businessId).eq("user_id",user.id).maybeSingle();
   if(!member||!["owner","admin"].includes(String(member.role))) return json({error:"Only the business owner or administrator can rename this workspace."},403);
   const {data,error}=await admin.from("businesses").update({name}).eq("id",businessId).select("id,name,business_type,status,paid_through,created_at").single(); if(error) throw error;
   return json({business:data});
  }
  return json({error:"Unknown business action."},404);
 }catch(e){console.error("twiga-businesses",e);return json({error:e instanceof Error?e.message:"Could not complete this business action."},500);}
});