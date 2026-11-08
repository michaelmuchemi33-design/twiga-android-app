/* Remove demo-only UI records and show only real TWIGA business data. */
(function(){
  if(!window.R || !window.$ || !window.route) return;
  window.calls = new Proxy([], {get:function(target,key){if(key==='0')return {st:'done',more:[]};return Reflect.get(target,key)}});
  window.sel = 0;
  window.stats = function(){
    var n=0;
    if($("sCalls"))$("sCalls").textContent="0";
    if($("sCallsS"))$("sCallsS").textContent="No call records yet";
    if($("sBook"))$("sBook").textContent="0";
    if($("sTrans"))$("sTrans").textContent="0";
    if($("sAttn"))$("sAttn").textContent="0";
    if($("navLive"))$("navLive").textContent="0";
  };
  window.liveList = function(){
    if($("liveList"))$("liveList").innerHTML='<div class="empty-state"><b>No active calls</b><span>When a real call reaches the phone, its live status will appear here.</span></div>';
  };
  window.tabs = function(){if($("tabs"))$("tabs").innerHTML="";};
  window.rows = function(){
    if($("rows"))$("rows").innerHTML='<div class="empty-state"><b>No call history yet</b><span>Real phone and AI call records will appear here after the first connected call.</span></div>';
  };
  window.detail = function(){
    if($("detail"))$("detail").innerHTML='<div class="empty-state detail-empty"><div class="empty-icon">☎</div><b>Call details will appear here</b><span>No sample transcripts or invented callers are shown.</span></div>';
  };
  window.render = function(){stats();liveList();tabs();rows();detail();};
  window.R.history=function(){return["Real call records from your paired phone and AI line. Demo conversations have been removed.",C("Call history",'<div id="realHistory" class="pb empty-state"><b>No calls recorded yet</b><span>Connect your Android phone and complete a real call to populate this page.</span></div>')]};
  window.R.customers=function(){return["Customer information is shown only when it has been saved to your business workspace.",C("Customers",'<div class="pb empty-state"><b>No customer records loaded</b><span>Connect Google Contacts or add customers through a completed order. Demo customer profiles have been removed.</span></div>')]};
  window.R.services=function(){
    setTimeout(async function(){
      var target=$("serviceRows");if(!target)return;
      try{
        var data=await L("listItems"),items=(data.items||[]).filter(function(x){return x.Active===1||x.Active===true});
        target.innerHTML=items.length?T(["Name","Type","Price","Stock"],items.map(function(x){return[esc(x.Name||"—"),esc(x.Kind||"Service"),"KSh "+Number(x.Price||0).toLocaleString(),x.TrackStock?String(x.Stock||0):"Not tracked"]}),"No active items in your catalogue."):"No active services or products are configured yet.";
      }catch(e){target.textContent="Could not load your live catalogue: "+e.message}
    },0);
    return["Live products and services from your business catalogue. No invented prices or sample services.",C("Your catalogue",'<div id="serviceRows" class="pb sub">Loading your catalogue…</div>')];
  };
  window.R.ai=function(){setTimeout(intLoad,0);return["Configure your AI receptionist using the settings saved to your business account. A green status means configured, not proof that a live SIM audio bridge has passed a call test.",C("AI receptionist status",'<div id="intRows" class="pb sub">Checking saved AI settings…</div>')+C("Voice test",'<div class="pb"><b>Test required</b><span class="sub">Place a real test call and confirm that the caller hears the AI and the AI can hear the caller before enabling automatic answering for customers.</span></div>')]};
  window.R.campaigns=function(){return["Outbound automation is not enabled in production yet. No sample campaign results are displayed.",C("Scheduled campaigns",'<div class="pb empty-state"><b>Campaigns are not active</b><span>Calling windows, opt-out checks, budgets and consent controls must be connected before scheduled calls can be enabled.</span></div>')]};
  window.R.staff=function(){return["Set up real staff contacts before enabling call transfers.",C("Staff and transfers",'<div class="pb empty-state"><b>No staff transfer list configured</b><span>Add verified staff names and phone numbers in business settings before routing customer calls.</span></div>')]};
  window.R.whatsapp=function(){return["WhatsApp messaging can open a customer chat with a prepared message. Automatic sending and WhatsApp voice-call answering are not enabled.",C("WhatsApp connection",'<div class="pb"><b>WhatsApp Business connection required</b><span class="sub">A click-to-chat link can open WhatsApp with a prepared order or booking message. The customer or staff member must still press Send. Automatic WhatsApp voice-call answering is not supported by the standard public WhatsApp integration.</span><div class="sub">Connect an approved WhatsApp Business Platform provider before enabling automated customer messages.</div></div>')]};
  window.render();
  window.route(1);
})();
