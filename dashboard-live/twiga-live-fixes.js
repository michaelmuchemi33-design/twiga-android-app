/* Live TWIGA data screens. Keep the original AI and catalogue forms intact. */
(function(){
  if(!window.R || !window.$ || !window.route) return;
  var K=window.TWIGA_CONFIG||{};
  window.calls = new Proxy([], {get:function(target,key){if(key==='0')return {st:'done',more:[]};return Reflect.get(target,key)}});
  window.sel = 0;
  window.stats = function(){
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

  async function googleRequest(action, extra){
    var session=(await sb.auth.getSession()).data.session;
    if(!session)throw new Error("Sign in to TWIGA again.");
    var response=await fetch(K.supabaseUrl+"/functions/v1/google-workspace",{
      method:"POST",
      headers:{apikey:K.supabaseKey,Authorization:"Bearer "+session.access_token,"Content-Type":"application/json"},
      body:JSON.stringify(Object.assign({action:action,businessId:BIZ.id},extra||{}))
    });
    var data=await response.json().catch(function(){return {}});
    if(!response.ok)throw new Error(data.error||("Google "+action+" failed ("+response.status+")."));
    return data;
  }
  function dateText(value){
    if(!value)return "—";
    var d=new Date(value);
    return Number.isFinite(d.getTime())?d.toLocaleString():"—";
  }
  function empty(title,detail){
    return '<div class="empty-state"><b>'+esc(title)+'</b><span>'+esc(detail)+'</span></div>';
  }

  // Customer records are merged from the TWIGA database, the phone ledger and Google Contacts.
  window.R.customers=function(){
    setTimeout(loadCustomers,0);
    return ["Real customer records from TWIGA and connected contacts. No sample customer profiles are shown.",C("Customers",'<div id="twigaCustomers" class="pb sub">Loading customer records…</div>')];
  };
  async function loadCustomers(){
    var target=$("twigaCustomers");if(!target)return;
    try{
      var results=await Promise.all([
        sb.from("twiga_customers").select("name,phone,notes,created_at").eq("business_id",BIZ.id).order("created_at",{ascending:false}).limit(200),
        L("listContacts",{limit:200}).catch(function(e){return {contacts:[],ledgerError:e.message};}),
        googleRequest("status").catch(function(e){return {connected:false,error:e.message};})
      ]);
      var db=results[0], ledger=results[1], google=results[2];
      if(db.error)throw db.error;
      var rows=[],seen={};
      function add(name,phone,email,notes,source){
        var key=String(phone||name||"").replace(/[^0-9a-z]/gi,"").toLowerCase();
        if(!key||seen[key])return;
        seen[key]=true;rows.push([esc(name||"—"),esc(phone||"—"),esc(email||"—"),esc(notes||source||"")]);
      }
      (db.data||[]).forEach(function(x){add(x.name,x.phone,"",x.notes,"TWIGA")});
      (ledger.contacts||[]).forEach(function(x){add(x.Name,x.Phone,x.Email,"Phone/desk contact")});
      if(google.connected && google.refreshReady){
        try{
          var people=await googleRequest("contacts.list",{pageSize:100});
          (people.connections||[]).forEach(function(p){
            var name=(p.names&&p.names[0]&&p.names[0].displayName)||"Google contact";
            var phone=(p.phoneNumbers&&p.phoneNumbers[0]&&p.phoneNumbers[0].value)||"";
            var email=(p.emailAddresses&&p.emailAddresses[0]&&p.emailAddresses[0].value)||"";
            add(name,phone,email,"Google Contacts");
          });
        }catch(e){google.error=e.message;}
      }
      var note=google.connected?"Google Contacts connected.": "Connect Google Workspace to include Google Contacts.";
      target.innerHTML=rows.length?T(["Name","Phone","Email","Notes / source"],rows,note):empty("No customers yet","Add a customer from an order or connect Google Contacts.");
      if(ledger.ledgerError)target.innerHTML+='<div class="sub">Phone ledger: '+esc(ledger.ledgerError)+'</div>';
      if(google.error)target.innerHTML+='<div class="sub">Google Contacts: '+esc(google.error)+'</div>';
    }catch(e){target.textContent="Could not load customers: "+e.message;}
  }

  // The history page reads actual SIM events and AI call records from Supabase.
  window.R.history=function(){
    setTimeout(loadHistory,0);
    return ["Real call records from the phone and AI receptionist. Example calls and transcripts are not displayed.",C("Call history",'<div id="twigaHistory" class="pb sub">Loading call records…</div>')];
  };
  async function loadHistory(){
    var target=$("twigaHistory");if(!target)return;
    try{
      var results=await Promise.all([
        sb.from("call_events").select("direction,number,state,duration_s,ai_on,started_at,updated_at").eq("business_id",BIZ.id).order("updated_at",{ascending:false}).limit(100),
        sb.from("twiga_call_records").select("provider,channel,direction,from_number,to_number,status,started_at,duration_seconds,summary").eq("business_id",BIZ.id).order("created_at",{ascending:false}).limit(100)
      ]);
      if(results[0].error)throw results[0].error;
      if(results[1].error)throw results[1].error;
      var rows=[];
      (results[0].data||[]).forEach(function(x){
        rows.push([dateText(x.started_at||x.updated_at),"SIM",esc(x.direction||"—"),esc(x.number||"Unknown"),G(esc(x.state||"—"),x.state==="missed"?"bad":"gr"),String(x.duration_s||0)+" s",x.ai_on?"AI enabled":"AI off"]);
      });
      (results[1].data||[]).forEach(function(x){
        rows.push([dateText(x.started_at),esc(x.provider||"AI"),esc(x.direction||"—"),esc(x.from_number||x.to_number||"Unknown"),G(esc(x.status||"—"),x.status==="ended"?"ok":"or"),String(x.duration_seconds||0)+" s",esc(x.summary||"")]);
      });
      rows.sort(function(a,b){return new Date(b[0]).getTime()-new Date(a[0]).getTime()});
      target.innerHTML=rows.length?T(["When","Source","Direction","Number","Status","Duration","Summary"],rows,"No calls have been recorded yet."):
        empty("No calls recorded yet","Connect the phone and complete a real call. AI call transcripts appear after the provider webhook records them.");
    }catch(e){target.textContent="Could not load call history: "+e.message;}
  }

  // Appointments and calendar combine the TWIGA booking table, ledger events and Google Calendar.
  window.R.appointments=function(){
    setTimeout(loadAppointments,0);
    return ["Appointments saved by the AI receptionist or your team. These are live records, not preview rows.",C("Appointments",'<div id="twigaAppointments" class="pb sub">Loading appointments…</div>')];
  };
  async function loadAppointments(){
    var target=$("twigaAppointments");if(!target)return;
    try{
      var results=await Promise.all([
        sb.from("twiga_appointments").select("customer_name,phone,scheduled_at,notes,status,google_event_url").eq("business_id",BIZ.id).order("scheduled_at",{ascending:true}).limit(200),
        L("listOrders",{limit:200}).catch(function(){return {orders:[]};})
      ]);
      if(results[0].error)throw results[0].error;
      var rows=(results[0].data||[]).map(function(x){return[dateText(x.scheduled_at),esc(x.customer_name||"—"),esc(x.phone||""),esc(x.notes||""),G(esc(x.status||"scheduled"),String(x.status).toLowerCase()==="confirmed"?"ok":"or"),x.google_event_url?'<a href="'+esc(x.google_event_url)+'" target="_blank" rel="noopener">Google event</a>':"—"]});
      (results[1].orders||[]).filter(function(x){return x.Type==="Appointment"&&x.Status!=="Cancelled";}).forEach(function(x){
        rows.push([esc(x.Date||"—"),esc(x.Customer||"—"),esc(x.Phone||""),esc(x.Notes||""),G(esc(x.Status||"New"),"or"),"TWIGA ledger"]);
      });
      target.innerHTML=rows.length?T(["When","Customer","Phone","Service / notes","Status","Calendar"],rows,"No appointments saved yet."):
        empty("No appointments yet","Bookings made by the AI or staff will appear here.");
    }catch(e){target.textContent="Could not load appointments: "+e.message;}
  }
  window.R.calendar=function(){
    setTimeout(loadCalendar,0);
    return ["Your real appointments from TWIGA and connected Google Calendar.",C("Calendar events",'<div id="twigaCalendar" class="pb sub">Loading calendar…</div>')];
  };
  async function loadCalendar(){
    var target=$("twigaCalendar");if(!target)return;
    try{
      var results=await Promise.all([
        L("listEvents",{from:new Date().toISOString()}).catch(function(e){return {events:[],ledgerError:e.message};}),
        sb.from("twiga_appointments").select("customer_name,scheduled_at,notes,status").eq("business_id",BIZ.id).gte("scheduled_at",new Date().toISOString()).order("scheduled_at",{ascending:true}).limit(100),
        googleRequest("status").catch(function(e){return {connected:false,error:e.message};})
      ]);
      if(results[1].error)throw results[1].error;
      var events=[];
      (results[0].events||[]).forEach(function(x){events.push([x.Start,x.Title,x.Notes||"TWIGA ledger","TWIGA"])}); 
      (results[1].data||[]).forEach(function(x){events.push([x.scheduled_at,x.customer_name,x.notes||x.status||"Appointment","TWIGA booking"])}); 
      var google=results[2];
      if(google.connected&&google.refreshReady){
        try{
          var now=new Date(),end=new Date(now);end.setDate(end.getDate()+30);
          var cal=await googleRequest("calendar.list",{timeMin:now.toISOString(),timeMax:end.toISOString()});
          (cal.items||[]).forEach(function(x){events.push([x.start&& (x.start.dateTime||x.start.date),x.summary||"Google event",x.description||"", "Google Calendar"])});
        }catch(e){google.error=e.message;}
      }
      events=events.filter(function(x){return x[0]}).sort(function(a,b){return new Date(a[0])-new Date(b[0])});
      var rows=events.map(function(x){return[dateText(x[0]),esc(x[1]||"—"),esc(x[2]||""),esc(x[3]) ]});
      target.innerHTML=rows.length?T(["When","Event","Details","Source"],rows,"No upcoming events."):
        empty("No upcoming events","Connect Google Calendar or save an appointment in TWIGA.");
      if(!google.connected)target.innerHTML+='<div class="sub">Google Calendar is not connected to this TWIGA business yet. Use Integrations → Connect Google Workspace.</div>';
      if(google.error)target.innerHTML+='<div class="sub">Google Calendar: '+esc(google.error)+'</div>';
      if(results[0].ledgerError)target.innerHTML+='<div class="sub">Business ledger: '+esc(results[0].ledgerError)+'</div>';
    }catch(e){target.textContent="Could not load calendar: "+e.message;}
  }

  // Keep non-live integrations explicitly disabled instead of showing fabricated successes.
  window.R.campaigns=function(){return["Outbound automation is not enabled in production yet. No sample campaign results are displayed.",C("Scheduled campaigns",'<div class="pb empty-state"><b>Campaigns are not active</b><span>Calling windows, opt-out checks, budgets and consent controls must be connected before scheduled calls can be enabled.</span></div>')]};
  window.R.staff=function(){return["Set up real staff contacts before enabling call transfers.",C("Staff and transfers",'<div class="pb empty-state"><b>No staff transfer list configured</b><span>Add verified staff names and phone numbers in business settings before routing customer calls.</span></div>')]};
  window.R.whatsapp=function(){return["WhatsApp messaging is not connected to this workspace yet.",C("WhatsApp connection",'<div class="pb"><b>WhatsApp Business connection required</b><span class="sub">Automatic sending is not enabled. Connect an approved WhatsApp Business Platform provider before enabling automated messages.</span></div>')]};

  window.render();
  window.route(1);
})();
