import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, x-client-info, apikey, content-type, x-twiga-admin-key", "access-control-allow-methods": "GET, POST, OPTIONS" };
const json = (body: unknown, status=200) => new Response(JSON.stringify(body), {status,headers:{...cors,"content-type":"application/json"}});
const env = (k:string) => Deno.env.get(k) ?? "";
const db = () => createClient(env("SUPABASE_URL"),env("SUPABASE_SERVICE_ROLE_KEY"),{auth:{persistSession:false}});
const normalisePhone=(v:string)=>{let p=v.replace(/\D/g,"");if(p.startsWith("0"))p="254"+p.slice(1);if(p.startsWith("7")||p.startsWith("1"))p="254"+p;return p;};
async function darajaToken(){const key=env("DARAJA_CONSUMER_KEY"),secret=env("DARAJA_CONSUMER_SECRET");if(!key||!secret)throw new Error("Daraja credentials are not configured");const basic=btoa(key+":"+secret);const r=await fetch(env("DARAJA_ENV")==="production"?"https://api.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials":"https://sandbox.safaricom.co.ke/oauth/v1/generate?grant_type=client_credentials",{headers:{authorization:"Basic "+basic}});const j=await r.json();if(!r.ok)throw new Error(j.errorMessage??"Daraja token request failed");return j.access_token as string;}
async function stk(phone:string,amount:number,account:string){const token=await darajaToken();const shortcode=env("DARAJA_SHORTCODE"),passkey=env("DARAJA_PASSKEY"),callback=env("DARAJA_CALLBACK_URL");if(!shortcode||!passkey||!callback)throw new Error("Daraja shortcode, passkey and public callback URL are required");const now=new Date();const stamp=now.getFullYear()+String(now.getMonth()+1).padStart(2,"0")+String(now.getDate()).padStart(2,"0")+String(now.getHours()).padStart(2,"0")+String(now.getMinutes()).padStart(2,"0")+String(now.getSeconds()).padStart(2,"0");const password=btoa(shortcode+passkey+stamp);const base=env("DARAJA_ENV")==="production"?"https://api.safaricom.co.ke":"https://sandbox.safaricom.co.ke";const r=await fetch(base+"/mpesa/stkpush/v1/processrequest",{method:"POST",headers:{authorization:"Bearer "+token,"content-type":"application/json"},body:JSON.stringify({BusinessShortCode:shortcode,Password:password,Timestamp:stamp,TransactionType:env("DARAJA_TRANSACTION_TYPE")||"CustomerPayBillOnline",Amount:amount,PartyA:phone,PartyB:shortcode,PhoneNumber:phone,CallBackURL:callback,AccountReference:account.slice(0,12),TransactionDesc:"Twiga subscription"})});const j=await r.json();if(!r.ok||j.ResponseCode!=="0")throw new Error(j.errorMessage??j.ResponseDescription??"STK request failed");return j;}
Deno.serve(async req=>{
 if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
 const url=new URL(req.url), path=url.pathname.split("/").pop()??"", supa=db();
 try{
  if(req.method==="POST"&&path==="register"){
   const b=await req.json();if(!b.businessName||!b.phone)return json({error:"Business name and phone are required"},400);
   const phone=normalisePhone(String(b.phone));if(!/^254[17]\d{8}$/.test(phone))return json({error:"Enter a valid Kenyan mobile number"},400);
   const {data:customer,error}=await supa.from("twiga_customers").insert({business_name:String(b.businessName).slice(0,120),contact_name:String(b.contactName??"").slice(0,120),phone,email:b.email??null,status:"trial",trial_started_at:new Date().toISOString(),trial_ends_at:new Date(Date.now()+7*86400000).toISOString()}).select("id,licence_key,trial_ends_at").single();
   if(error)throw error;
   await supa.from("twiga_subscriptions").insert({customer_id:customer.id,plan_code:"monthly",status:"trial",starts_at:new Date().toISOString(),ends_at:customer.trial_ends_at});
   return json({customerId:customer.id,licenceKey:customer.licence_key,trialEndsAt:customer.trial_ends_at});
  }
  if(req.method==="POST"&&path==="pay"){
   const b=await req.json(),phone=normalisePhone(String(b.phone??""));if(!b.licenceKey||!["monthly","annual"].includes(b.plan)||!/^254[17]\d{8}$/.test(phone))return json({error:"Valid licence, plan and Kenyan phone are required"},400);
   const {data:c,error:ce}=await supa.from("twiga_customers").select("id,licence_key").eq("licence_key",b.licenceKey).single();if(ce||!c)return json({error:"Licence not found"},404);
   const {data:plan,error:pe}=await supa.from("twiga_plans").select("*").eq("code",b.plan).eq("active",true).single();if(pe||!plan)return json({error:"Plan unavailable"},400);
   const {data:p,error:ie}=await supa.from("twiga_payments").insert({customer_id:c.id,plan_code:plan.code,amount_kes:plan.amount_kes,phone,status:"initiated"}).select("id").single();if(ie)throw ie;
   const result=await stk(phone,plan.amount_kes,"TWIGA"+p.id.slice(0,8));
   await supa.from("twiga_payments").update({merchant_request_id:result.MerchantRequestID,checkout_request_id:result.CheckoutRequestID,status:"pending"}).eq("id",p.id);
   return json({message:"STK Push sent. Complete the prompt on your phone.",checkoutRequestId:result.CheckoutRequestID});
  }
  if(req.method==="POST"&&path==="callback"){
   const body=await req.json(), meta=body?.Body?.stkCallback;if(!meta?.CheckoutRequestID)return json({ResultCode:0,ResultDesc:"Ignored"});
   if(Number(meta.ResultCode)===0){const items=meta.CallbackMetadata?.Item??[],get=(name:string)=>items.find((x:any)=>x.Name===name)?.Value;const receipt=String(get("MpesaReceiptNumber")??"");if(!receipt)return json({ResultCode:1,ResultDesc:"Missing receipt"},400);await supa.rpc("twiga_activate_payment",{p_checkout_id:meta.CheckoutRequestID,p_receipt:receipt,p_callback:body});}
   else await supa.from("twiga_payments").update({status:"failed",result_code:meta.ResultCode,result_description:meta.ResultDesc,raw_callback:body}).eq("checkout_request_id",meta.CheckoutRequestID);
   return json({ResultCode:0,ResultDesc:"Accepted"});
  }
  if(req.method==="POST"&&path==="licence"){
   const b=await req.json();if(!b.licenceKey)return json({error:"Licence key required"},400);
   const {data:c,error}=await supa.from("twiga_customers").select("id,status,trial_ends_at,device_id").eq("licence_key",b.licenceKey).single();if(error||!c)return json({valid:false,status:"not_found"},404);
   if(b.deviceId&&c.device_id&&b.deviceId!==c.device_id)return json({valid:false,status:"device_mismatch"},403);
   if(b.deviceId&&!c.device_id)await supa.from("twiga_customers").update({device_id:String(b.deviceId).slice(0,128)}).eq("id",c.id);
   const now=Date.now();const trialValid=c.status==="trial"&&c.trial_ends_at&&new Date(c.trial_ends_at).getTime()>now;
   const {data:s}=await supa.from("twiga_subscriptions").select("status,ends_at").eq("customer_id",c.id).order("ends_at",{ascending:false}).limit(1).maybeSingle();
   const paidValid=s?.status==="active"&&s.ends_at&&new Date(s.ends_at).getTime()>now;
   const valid=!!trialValid||!!paidValid;
   return json({valid,status:valid?(trialValid?"trial":"active"):(c.status==="suspended"?"suspended":"expired"),expiresAt:trialValid?c.trial_ends_at:s?.ends_at??null,serverTime:new Date().toISOString()});
  }
  if(path==="admin"){
   const secret=env("TWIGA_ADMIN_API_KEY");if(!secret||req.headers.get("x-twiga-admin-key")!==secret)return json({error:"Unauthorized"},401);
   if(req.method==="GET"){const [{data:customers},{data:payments},{data:plans}] = await Promise.all([supa.from("twiga_customers").select("id,business_name,phone,email,status,licence_key,trial_ends_at,created_at").order("created_at",{ascending:false}).limit(500),supa.from("twiga_payments").select("id,customer_id,plan_code,amount_kes,status,mpesa_receipt,created_at,paid_at").order("created_at",{ascending:false}).limit(500),supa.from("twiga_plans").select("*")]);return json({customers,payments,plans});}
   const b=await req.json();if(b.action==="set_price"&&["monthly","annual"].includes(b.plan)&&Number.isInteger(b.amountKes)&&b.amountKes>0){const {error}=await supa.from("twiga_plans").update({amount_kes:b.amountKes,updated_at:new Date().toISOString()}).eq("code",b.plan);if(error)throw error;return json({ok:true});}
   if(b.action==="suspend"||b.action==="reactivate"){const status=b.action==="suspend"?"suspended":"active";const {error}=await supa.from("twiga_customers").update({status}).eq("id",b.customerId);if(error)throw error;return json({ok:true});}
   return json({error:"Unknown admin action"},400);
  }
  if(req.method==="GET"&&path==="updates"){const r=await fetch("https://api.github.com/repos/michaelmuchemi33-design/unity-ai-receptionist-app/releases/tags/latest-apk",{headers:{"user-agent":"Twiga-Android"}});if(!r.ok)return json({available:false});const rel=await r.json();const asset=rel.assets?.find((a:any)=>a.name==="unity-ai-receptionist.apk");return json({available:!!asset,version:rel.tag_name,downloadUrl:asset?.browser_download_url,notes:rel.body??"",publishedAt:rel.published_at});}
  return json({error:"Not found"},404);
 }catch(e){console.error(e);return json({error:e instanceof Error?e.message:"Internal error"},500);}
});
