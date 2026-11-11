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
        window.TWIGA_LEDGER("listContacts",{limit:200}).catch(function(e){return {contacts:[],ledgerError:e.message};}),
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
        window.TWIGA_LEDGER("listOrders",{limit:200}).catch(function(){return {orders:[]};})
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
        window.TWIGA_LEDGER("listEvents",{from:new Date().toISOString()}).catch(function(e){return {events:[],ledgerError:e.message};}),
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
  window.R.campaigns=function(){return["Campaigns are in testing and are not available for live customer calls yet.",C("Campaigns · Testing",'<div class="pb empty-state"><b>Testing — not enabled</b><span>Scheduling, consent checks, call windows and reporting must pass testing before activation.</span></div>')]};
  window.R.staff=function(){return["Staff and call transfers are in testing and cannot be used for live customer calls yet.",C("Staff & transfers · Testing",'<div class="pb empty-state"><b>Testing — not enabled</b><span>Staff routing and transfer destinations are not active.</span></div>')]};
  window.R.whatsapp=function(){return["WhatsApp is coming soon.",C("WhatsApp · Coming soon",'<div class="pb empty-state"><b>Coming soon</b><span>WhatsApp messaging will appear here after the approved business messaging integration is ready.</span></div>')]};


  // POS and catalogue use the same Supabase records as the AI receptionist.
  // Product photos are private objects scoped to the owning business.
  var TWIGA_IMAGE_BUCKET="twiga-product-images";
  var serviceRows=[];
  var editingProductId=null;
  var posProducts=[];
  var posCart=[];
  var posBusy=false;
  var posLastMessage="";
  function twigaDb(){
    if(!window.TWIGA_DB||!window.TWIGA_DB.client)throw new Error("TWIGA database session is not ready. Sign in again.");
    return window.TWIGA_DB.client;
  }
  function twigaBusinessId(){
    var id=window.TWIGA_DB&&window.TWIGA_DB.businessId?window.TWIGA_DB.businessId():null;
    if(!id)throw new Error("No active TWIGA business was found for this account.");
    return id;
  }
  function twigaUserId(){
    return window.TWIGA_DB&&window.TWIGA_DB.userId?window.TWIGA_DB.userId():null;
  }
  function money(value){return "KSh "+Number(value||0).toLocaleString("en-KE",{maximumFractionDigits:2})}
  async function signedProductImage(key){
    if(!key)return "";
    var result=await twigaDb().storage.from(TWIGA_IMAGE_BUCKET).createSignedUrl(key,3600);
    if(result.error)throw result.error;
    return result.data&&result.data.signedUrl?result.data.signedUrl:"";
  }
  async function fetchTwigaProducts(activeOnly){
    var q=twigaDb().from("twiga_products")
      .select("id,business_id,name,description,category,price,currency,unit,stock_quantity,low_stock_threshold,image_key,is_active,item_type,track_stock,created_at")
      .eq("business_id",twigaBusinessId())
      .order("created_at",{ascending:false});
    if(activeOnly)q=q.eq("is_active",true);
    var result=await q;
    if(result.error)throw result.error;
    var rows=result.data||[];
    return Promise.all(rows.map(async function(row){
      var url="";
      try{url=await signedProductImage(row.image_key)}catch(e){row.imageError=e.message}
      row.imageUrl=url;
      return row;
    }));
  }
  function productImage(item,size){
    var w=size||68;
    return item.imageUrl
      ? '<img src="'+esc(item.imageUrl)+'" alt="'+esc(item.name)+'" loading="lazy" style="width:'+w+'px;height:'+w+'px;object-fit:cover;border-radius:12px;background:var(--soft)">'
      : '<div aria-label="No product photo" style="width:'+w+'px;height:'+w+'px;display:grid;place-items:center;border-radius:12px;background:var(--soft);color:var(--mute);font-size:11px">Photo</div>';
  }
  function productKindName(item){return item.item_type==="service"?"Service":"Product"}

  window.R.services=function(){
    setTimeout(loadTwigaCatalogue,0);
    return [
      "Manage the real catalogue used by your POS and AI receptionist. Add products or services, prices, stock and product photos.",
      '<div class="g2">'+
      C("Add or edit an item",
        '<div class="pb" style="display:grid;gap:9px">'+
        '<input type="hidden" id="twigaProductId">'+
        '<label>Name<input id="twigaProductName" type="text" maxlength="180" required placeholder="e.g. Shea butter 250g" style="width:100%"></label>'+
        '<label>Description<textarea id="twigaProductDescription" rows="2" maxlength="1500" placeholder="What the customer receives" style="width:100%"></textarea></label>'+
        '<label>Category<input id="twigaProductCategory" type="text" maxlength="100" placeholder="Hair care, haircut, delivery…" style="width:100%"></label>'+
        '<div class="g2"><label>Type<select id="twigaProductType" style="width:100%"><option value="product">Product for sale</option><option value="service">Service / appointment</option></select></label>'+
        '<label>Price (KES)<input id="twigaProductPrice" type="number" min="0" step="0.01" required style="width:100%"></label></div>'+
        '<div class="g2" id="twigaStockFields"><label>Current stock<input id="twigaProductStock" type="number" min="0" step="1" value="0" style="width:100%"></label>'+
        '<label>Low-stock alert at<input id="twigaProductLowStock" type="number" min="0" step="1" value="3" style="width:100%"></label></div>'+
        '<label>Product photo (JPG, PNG or WebP; max 5 MB)<input id="twigaProductPhoto" type="file" accept="image/jpeg,image/png,image/webp" style="width:100%"></label>'+
        '<div id="twigaProductPreview" class="sub">Add a photo to show the item in your catalogue and POS.</div>'+
        '<div class="row" style="gap:8px"><button class="btn pri" data-product-save>Save item</button><button class="btn" data-product-reset type="button">Clear</button></div>'+
        '<div id="twigaProductMessage" class="sub" aria-live="polite"></div></div>'
      )+
      C("Catalogue",
        '<div class="pb"><div class="row" style="justify-content:space-between;gap:8px"><b>Products and services</b><button class="btn sm" data-product-refresh>Refresh</button></div>'+
        '<div id="twigaProductList" class="sub" style="padding-top:10px">Loading catalogue…</div></div>'
      )+
      '</div>'
    ];
  };
  function resetProductForm(){
    editingProductId=null;
    ["twigaProductId","twigaProductName","twigaProductDescription","twigaProductCategory","twigaProductPrice","twigaProductStock","twigaProductLowStock"].forEach(function(id){var e=$(id);if(e)e.value=id==="twigaProductStock"?"0":id==="twigaProductLowStock"?"3":""});
    if($("twigaProductType"))$("twigaProductType").value="product";
    if($("twigaProductPhoto"))$("twigaProductPhoto").value="";
    if($("twigaProductPreview"))$("twigaProductPreview").textContent="Add a photo to show the item in your catalogue and POS.";
    if($("twigaProductMessage"))$("twigaProductMessage").textContent="";
    if($("twigaStockFields"))$("twigaStockFields").hidden=false;
  }
  async function loadTwigaCatalogue(){
    var target=$("twigaProductList");if(!target)return;
    try{
      serviceRows=await fetchTwigaProducts(false);
      if(!serviceRows.length){target.innerHTML=empty("Your catalogue is empty","Add your first product or service. It will be available to the AI receptionist and POS.");return}
      target.innerHTML='<div style="display:grid;gap:10px">'+serviceRows.map(function(item){
        return '<div class="row" style="align-items:flex-start;gap:10px;padding:10px;border:1px solid var(--line);border-radius:14px">'+
          productImage(item,68)+
          '<div style="flex:1;min-width:0"><b>'+esc(item.name)+'</b><div class="sub">'+esc(productKindName(item))+' · '+money(item.price)+'</div>'+
          '<div class="sub">'+esc(item.category||item.description||"")+'</div>'+
          '<div class="sub">'+(item.track_stock?"Stock: "+Number(item.stock_quantity||0)+" · Low alert: "+Number(item.low_stock_threshold||0):"Stock tracking off")+'</div></div>'+
          '<div style="display:grid;gap:6px;justify-items:end"><button class="btn sm" data-product-edit="'+esc(item.id)+'">Edit</button>'+
          '<button class="btn sm" data-product-archive="'+esc(item.id)+'">Archive</button></div></div>';
      }).join("")+'</div>';
      if($("twigaProductMessage"))$("twigaProductMessage").textContent=serviceRows.length+" catalogue item(s) loaded from TWIGA.";
    }catch(e){target.textContent="Could not load catalogue: "+e.message}
  }
  function populateProductForm(item){
    editingProductId=item.id;
    $("twigaProductId").value=item.id;
    $("twigaProductName").value=item.name||"";
    $("twigaProductDescription").value=item.description||"";
    $("twigaProductCategory").value=item.category||"";
    $("twigaProductPrice").value=String(item.price||0);
    $("twigaProductType").value=item.item_type||"product";
    $("twigaProductStock").value=String(item.stock_quantity||0);
    $("twigaProductLowStock").value=String(item.low_stock_threshold||0);
    $("twigaStockFields").hidden=item.item_type==="service";
    $("twigaProductPhoto").value="";
    $("twigaProductPreview").innerHTML=productImage(item,90)+(item.image_key?'<span class="sub">Current photo. Choose a new image to replace it.</span>':'<span class="sub">No photo uploaded.</span>');
    $("twigaProductMessage").textContent="Editing "+item.name+". Save to apply changes.";
    window.scrollTo({top:0,behavior:"smooth"});
  }
  async function saveTwigaProduct(){
    var db=twigaDb(),businessId=twigaBusinessId(),userId=twigaUserId();
    var name=$("twigaProductName").value.trim();
    var price=Number($("twigaProductPrice").value);
    var type=$("twigaProductType").value;
    var stock=type==="service"?0:Number($("twigaProductStock").value||0);
    var low=type==="service"?0:Number($("twigaProductLowStock").value||0);
    var file=$("twigaProductPhoto").files&&$("twigaProductPhoto").files[0];
    if(!name)throw new Error("Enter the product or service name.");
    if(!Number.isFinite(price)||price<0)throw new Error("Enter a valid price in KSh.");
    if(!Number.isFinite(stock)||stock<0||!Number.isFinite(low)||low<0)throw new Error("Stock values cannot be negative.");
    if(file){
      if(!["image/jpeg","image/png","image/webp"].includes(file.type))throw new Error("Use a JPG, PNG or WebP product photo.");
      if(file.size>5*1024*1024)throw new Error("Product photos must be 5 MB or smaller.");
    }
    var old=serviceRows.find(function(x){return x.id===editingProductId})||null;
    var imageKey=old&&old.image_key?old.image_key:null;
    var newKey=null;
    if(file){
      var ext=file.type==="image/jpeg"?"jpg":file.type==="image/png"?"png":"webp";
      newKey=businessId+"/"+(window.crypto&&crypto.randomUUID?crypto.randomUUID():String(Date.now())+"-"+Math.random().toString(36).slice(2))+"."+ext;
      var upload=await db.storage.from(TWIGA_IMAGE_BUCKET).upload(newKey,file,{cacheControl:"3600",upsert:false,contentType:file.type});
      if(upload.error)throw new Error("Photo upload failed: "+upload.error.message);
      imageKey=newKey;
    }
    var row={
      business_id:businessId,
      created_by:userId,
      name:name,
      description:$("twigaProductDescription").value.trim(),
      category:$("twigaProductCategory").value.trim(),
      price:price,
      currency:"KES",
      unit:type==="service"?"service":"piece",
      item_type:type,
      track_stock:type==="product",
      stock_quantity:stock,
      low_stock_threshold:low,
      image_key:imageKey,
      is_active:true,
      updated_at:new Date().toISOString()
    };
    var result;
    if(editingProductId){
      delete row.created_by;
      result=await db.from("twiga_products").update(row).eq("id",editingProductId).eq("business_id",businessId).select("id").single();
    }else{
      result=await db.from("twiga_products").insert(row).select("id").single();
    }
    if(result.error){
      if(newKey)await db.storage.from(TWIGA_IMAGE_BUCKET).remove([newKey]).catch(function(){});
      throw result.error;
    }
    if(newKey&&old&&old.image_key&&old.image_key!==newKey){
      await db.storage.from(TWIGA_IMAGE_BUCKET).remove([old.image_key]).catch(function(){});
    }
    resetProductForm();
    await loadTwigaCatalogue();
  }
  document.addEventListener("change",function(e){
    if(e.target.id==="twigaProductType"&&$("twigaStockFields"))$("twigaStockFields").hidden=e.target.value==="service";
    if(e.target.id==="twigaProductPhoto"){
      var f=e.target.files&&e.target.files[0],target=$("twigaProductPreview");
      if(!target)return;
      if(!f){target.textContent="Add a photo to show the item in your catalogue and POS.";return}
      if(!["image/jpeg","image/png","image/webp"].includes(f.type)||f.size>5*1024*1024){target.textContent="Choose a JPG, PNG or WebP image no larger than 5 MB.";e.target.value="";return}
      var url=URL.createObjectURL(f);
      target.innerHTML='<img src="'+esc(url)+'" alt="Selected product photo" style="width:90px;height:90px;object-fit:cover;border-radius:12px"> <span class="sub">Photo ready to upload.</span>';
    }
  });
  document.addEventListener("click",async function(e){
    var edit=e.target.closest("[data-product-edit]");
    if(edit){var row=serviceRows.find(function(x){return x.id===edit.dataset.productEdit});if(row)populateProductForm(row);return}
    if(e.target.closest("[data-product-reset]")){resetProductForm();return}
    if(e.target.closest("[data-product-refresh]")){await loadTwigaCatalogue();return}
    var save=e.target.closest("[data-product-save]");
    if(save){
      var msg=$("twigaProductMessage");save.disabled=true;
      try{if(msg)msg.textContent="Saving item and photo…";await saveTwigaProduct();if(msg)msg.textContent="Item saved."}
      catch(err){if(msg)msg.textContent="Could not save item: "+err.message}
      finally{save.disabled=false}
      return;
    }
    var archive=e.target.closest("[data-product-archive]");
    if(archive){
      var id=archive.dataset.productArchive;
      if(!confirm("Archive this item? It will no longer appear in POS or the AI catalogue."))return;
      var out=await twigaDb().from("twiga_products").update({is_active:false,updated_at:new Date().toISOString()}).eq("id",id).eq("business_id",twigaBusinessId());
      if(out.error){if($("twigaProductMessage"))$("twigaProductMessage").textContent=out.error.message}
      else await loadTwigaCatalogue();
    }
  });

  function posTotal(){return posCart.reduce(function(total,row){return total+Number(row.product.price||0)*row.quantity},0)}
  function drawTwigaPos(){
    if(!$("twigaPosProducts")||!$("twigaPosCart"))return;
    var needle=($("twigaPosSearch")&&$("twigaPosSearch").value||"").toLowerCase();
    var list=posProducts.filter(function(p){return !needle||String(p.name+" "+p.description+" "+p.category).toLowerCase().includes(needle)});
    $("twigaPosProducts").innerHTML=list.length?'<div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:10px">'+list.map(function(p){
      var disabled=p.track_stock&&Number(p.stock_quantity)<=0;
      return '<div class="card" style="padding:10px;display:grid;gap:7px">'+productImage(p,88)+'<b>'+esc(p.name)+'</b><div class="sub">'+esc(productKindName(p))+' · '+money(p.price)+'</div>'+
        '<div class="sub">'+(p.track_stock?"Stock: "+Number(p.stock_quantity):"Available service")+'</div>'+
        '<button class="btn sm pri" data-twiga-pos-add="'+esc(p.id)+'" '+(disabled?'disabled':'')+'>Add to sale</button></div>';
    }).join("")+'</div>':empty("No matching items","Add products or services in Services & Pricing.");
    var rows=posCart.map(function(row,index){
      return '<div class="row" style="justify-content:space-between;gap:8px;padding:9px 0;border-bottom:1px solid var(--line)"><div style="flex:1"><b>'+esc(row.product.name)+'</b><div class="sub">'+money(row.product.price)+' each</div></div>'+
        '<div class="row" style="gap:5px"><button class="btn sm" data-twiga-pos-qty="'+index+'" data-delta="-1">−</button><b>'+row.quantity+'</b><button class="btn sm" data-twiga-pos-qty="'+index+'" data-delta="1">+</button><button class="btn sm" data-twiga-pos-remove="'+index+'">×</button></div></div>';
    }).join("");
    $("twigaPosCart").innerHTML=(rows||empty("Cart is empty","Select products or services to start a sale."))+
      '<div class="row" style="justify-content:space-between;padding:14px 0;font-size:18px"><b>Total</b><b>'+money(posTotal())+'</b></div>'+
      '<div class="pb" style="display:grid;gap:8px"><label>Customer<input id="twigaPosCustomer" placeholder="Walk-in customer" style="width:100%"></label>'+
      '<label>Phone<input id="twigaPosPhone" type="tel" placeholder="07xx xxx xxx" style="width:100%"></label>'+
      '<label>Payment<select id="twigaPosPayment" style="width:100%"><option>Cash</option><option>Pay later</option><option disabled>M-Pesa (not enabled)</option></select></label>'+
      '<button class="btn pri" data-twiga-pos-checkout '+(!posCart.length||posBusy?'disabled':'')+'>'+(posBusy?'Saving…':'Complete sale')+'</button>'+
      '<button class="btn" data-twiga-pos-clear '+(!posCart.length||posBusy?'disabled':'')+'>Clear cart</button>'+
      '<div id="twigaPosMessage" class="sub" aria-live="polite">'+esc(posLastMessage)+'</div></div>';
  }
  async function loadTwigaPos(){
    if(!$("twigaPosProducts"))return;
    $("twigaPosProducts").textContent="Loading catalogue…";
    try{
      posProducts=await fetchTwigaProducts(true);
      drawTwigaPos();
      await loadTwigaPosSales();
    }catch(err){$("twigaPosProducts").textContent="Could not load the POS catalogue: "+err.message}
  }
  async function loadTwigaPosSales(){
    if(!$("twigaPosRecent"))return;
    var result=await twigaDb().from("twiga_orders")
      .select("id,order_number,customer_name,total_amount,status,payment_status,payment_method,created_at,items")
      .eq("business_id",twigaBusinessId()).order("created_at",{ascending:false}).limit(10);
    if(result.error){$("twigaPosRecent").textContent="Could not load sales: "+result.error.message;return}
    var rows=(result.data||[]).map(function(x){return[
      esc(x.order_number||x.id.slice(0,8)),
      esc(x.customer_name),
      money(x.total_amount),
      G(esc(x.payment_status||"unpaid"),x.payment_status==="paid"?"ok":"or"),
      esc(new Date(x.created_at).toLocaleString())
    ]});
    $("twigaPosRecent").innerHTML=rows.length?T(["Order","Customer","Total","Payment","Created"],rows,"No sales yet."):
      empty("No sales recorded yet","Complete a sale to see it in this list.");
  }
  window.R.pos=function(){
    setTimeout(loadTwigaPos,0);
    return [
      "Sell products and services from one catalogue. Product stock is checked and decremented when a sale is saved.",
      '<div class="g2"><div>'+C("Catalogue",'<div class="pb"><input id="twigaPosSearch" type="search" placeholder="Search products and services" style="width:100%"><div id="twigaPosProducts" class="sub" style="padding-top:10px">Loading catalogue…</div></div>')+'</div>'+
      '<div>'+C("Current sale",'<div id="twigaPosCart" class="pb">Loading cart…</div>')+'</div></div>'+
      C("Recent sales",'<div id="twigaPosRecent" class="pb sub">Loading sales…</div>')
    ];
  };
  document.addEventListener("input",function(e){if(e.target.id==="twigaPosSearch")drawTwigaPos()});
  document.addEventListener("click",async function(e){
    var add=e.target.closest("[data-twiga-pos-add]");
    if(add){
      var product=posProducts.find(function(p){return p.id===add.dataset.twigaPosAdd});if(!product)return;
      var row=posCart.find(function(x){return x.product.id===product.id}),next=row?row.quantity+1:1;
      if(product.track_stock&&next>Number(product.stock_quantity||0)){posLastMessage="Not enough stock for "+product.name+".";drawTwigaPos();return}
      if(row)row.quantity=next;else posCart.push({product:product,quantity:1});
      posLastMessage="";drawTwigaPos();return;
    }
    var qty=e.target.closest("[data-twiga-pos-qty]");
    if(qty){
      var index=Number(qty.dataset.twigaPosQty),cartRow=posCart[index],next=cartRow?cartRow.quantity+Number(qty.dataset.delta):0;
      if(!cartRow)return;
      if(next<=0)posCart.splice(index,1);
      else if(cartRow.product.track_stock&&next>Number(cartRow.product.stock_quantity||0)){posLastMessage="Not enough stock for "+cartRow.product.name+".";drawTwigaPos();return}
      else cartRow.quantity=next;
      drawTwigaPos();return;
    }
    var remove=e.target.closest("[data-twiga-pos-remove]");
    if(remove){posCart.splice(Number(remove.dataset.twigaPosRemove),1);drawTwigaPos();return}
    if(e.target.closest("[data-twiga-pos-clear]")){posCart=[];posLastMessage="Cart cleared.";drawTwigaPos();return}
    if(e.target.closest("[data-twiga-pos-checkout]")){
      if(posBusy||!posCart.length)return;
      posBusy=true;posLastMessage="Saving sale and checking stock…";drawTwigaPos();
      try{
        var result=await twigaDb().rpc("twiga_pos_checkout",{
          p_business_id:twigaBusinessId(),
          p_customer_name:$("twigaPosCustomer")?$("twigaPosCustomer").value.trim():"",
          p_customer_phone:$("twigaPosPhone")?$("twigaPosPhone").value.trim():"",
          p_payment_method:$("twigaPosPayment")?$("twigaPosPayment").value:"Cash",
          p_items:posCart.map(function(x){return{product_id:x.product.id,quantity:x.quantity}})
        });
        if(result.error)throw result.error;
        var order=result.data||{};
        posCart=[];
        posLastMessage="Sale "+(order.order_number||"saved")+" completed. Total "+money(order.total_amount)+".";
        await loadTwigaPos();
        if($("twigaPosMessage"))$("twigaPosMessage").textContent=posLastMessage;
      }catch(err){posLastMessage="Sale could not be saved: "+err.message}
      finally{posBusy=false;drawTwigaPos();await loadTwigaPosSales().catch(function(){});}
    }
  });

  window.R.orders=function(){
    setTimeout(loadTwigaOrders,0);
    return ["All orders saved by staff, POS and the AI receptionist. These records share the same TWIGA business order table.",C("Orders",'<div id="twigaOrders" class="pb sub">Loading orders…</div>')];
  };
  async function loadTwigaOrders(){
    var target=$("twigaOrders");if(!target)return;
    try{
      var result=await twigaDb().from("twiga_orders")
        .select("id,order_number,customer_name,customer_phone,total_amount,description,status,payment_status,payment_method,created_at,items")
        .eq("business_id",twigaBusinessId()).order("created_at",{ascending:false}).limit(200);
      if(result.error)throw result.error;
      var rows=(result.data||[]).map(function(o){
        var details=Array.isArray(o.items)?o.items.map(function(x){return (x.name||"Item")+" x"+(x.quantity||1)}).join(", "):o.description||"";
        return [esc(o.order_number||o.id.slice(0,8)),esc(o.customer_name),esc(o.customer_phone||""),esc(details||o.description||""),money(o.total_amount),G(esc(o.status||"pending"),String(o.status).toLowerCase()==="paid"?"ok":"or"),G(esc(o.payment_status||"unpaid"),o.payment_status==="paid"?"ok":"or"),esc(new Date(o.created_at).toLocaleString())];
      });
      target.innerHTML=rows.length?T(["Order","Customer","Phone","Items","Total","Status","Payment","Created"],rows,"No orders yet."):
        empty("No orders yet","Orders created by the AI, your team and the POS will appear here.");
    }catch(e){target.textContent="Could not load orders: "+e.message}
  }
  window.R.payments=function(){
    setTimeout(loadTwigaPayments,0);
    return ["Actual order payment status from the shared TWIGA order ledger. M-Pesa is not yet enabled.",'<div class="stats" id="twigaPayStats">'+S("Collected","—","Loading")+S("Outstanding","—","Loading")+S("Orders","—","Loading")+'</div>'+C("Payment ledger",'<div id="twigaPayRows" class="pb sub">Loading payment records…</div>')];
  };
  async function loadTwigaPayments(){
    var target=$("twigaPayRows");if(!target)return;
    try{
      var result=await twigaDb().from("twiga_orders")
        .select("order_number,customer_name,total_amount,payment_status,payment_method,status,created_at")
        .eq("business_id",twigaBusinessId()).order("created_at",{ascending:false}).limit(200);
      if(result.error)throw result.error;
      var orders=result.data||[];
      var collected=orders.filter(function(x){return x.payment_status==="paid"}).reduce(function(n,x){return n+Number(x.total_amount||0)},0);
      var outstanding=orders.filter(function(x){return x.payment_status!=="paid"}).reduce(function(n,x){return n+Number(x.total_amount||0)},0);
      if($("twigaPayStats"))$("twigaPayStats").innerHTML=S("Collected",money(collected),"Marked paid")+S("Outstanding",money(outstanding),"Not yet paid")+S("Orders",String(orders.length),"All channels");
      target.innerHTML=orders.length?T(["Order","Customer","Total","Payment","Method","Created"],orders.map(function(x){return[
        esc(x.order_number||"—"),esc(x.customer_name),money(x.total_amount),
        G(esc(x.payment_status||"unpaid"),x.payment_status==="paid"?"ok":"or"),
        esc(x.payment_method||"—"),esc(new Date(x.created_at).toLocaleString())
      ]}),"No order payments yet."):empty("No order payments yet","Cash and pay-later checkout results will be recorded here.");
    }catch(e){target.textContent="Could not load payments: "+e.message}
  }

  // Structured business setup, real overview, reports and follow-ups.
  var PROFILE_FIELDS=[
    ["business_summary","Describe your business","Start here: what is your business, where is it, and what should customers know?"],
    ["what_they_sell","What you sell","Products, packages, brands and price ranges."],
    ["what_they_do","What you do","Services, appointments, repairs, deliveries or other work."],
    ["products_and_services","Products and services","List names, sizes, variations and typical durations. Keep exact prices in the catalogue."],
    ["target_customers","Target customers","Who do you normally serve?"],
    ["unique_selling_points","What makes the business different","Quality, location, speed, warranties or special offers."],
    ["service_area","Service area","Counties, towns, delivery radius or branches."],
    ["opening_hours","Opening hours","Opening and closing times, days and public-holiday rules."],
    ["delivery_and_payment","Delivery and payment","Delivery options, cash, pay-later and payment rules. Do not promise M-Pesa until enabled."],
    ["returns_and_refunds","Returns and refunds","Refund, exchange and cancellation policy."],
    ["frequently_asked_questions","Frequently asked questions","Write common questions and accurate answers."],
    ["assistant_instructions","Special AI instructions","How the receptionist should handle unusual requests, uncertainty and escalation."]
  ];
  function profileFormHtml(row){
    return '<div class="g2">'+PROFILE_FIELDS.map(function(f){
      return '<label>'+esc(f[1])+'<textarea id="twigaProfile_'+f[0]+'" rows="'+(f[0]==="business_summary"?3:2)+'" maxlength="5000" placeholder="'+esc(f[2])+'" style="width:100%">'+esc(row[f[0]]||"")+'</textarea></label>';
    }).join("")+
    '<label>Brand voice<input id="twigaProfile_brand_voice" type="text" maxlength="120" value="'+esc(row.brand_voice||"Warm, helpful and professional")+'" style="width:100%"></label>'+
    '<label>Reply language<select id="twigaProfile_reply_language" style="width:100%"><option value="match_customer">Match the customer (English / Kiswahili)</option><option value="en">English</option><option value="sw">Kiswahili</option></select></label></div>';
  }
  async function loadBusinessProfile(){
    var target=$("twigaBusinessProfile");if(!target)return;
    try{
      var result=await twigaDb().from("twiga_business_profiles").select("*").eq("business_id",twigaBusinessId()).maybeSingle();
      if(result.error)throw result.error;
      var row=result.data||{};
      target.innerHTML=profileFormHtml(row)+
        '<div class="row" style="gap:8px;margin-top:12px"><button class="btn pri" data-profile-save>Save business information</button>'+
        '<span id="twigaProfileMessage" class="sub" aria-live="polite">These details are added to the AI receptionist instructions.</span></div>';
      if($("twigaProfile_reply_language"))$("twigaProfile_reply_language").value=row.reply_language||"match_customer";
    }catch(e){target.textContent="Could not load business profile: "+e.message}
  }
  // One shared business profile is the primary AI setup on both web and Android.
  window.R.ai=function(){
    setTimeout(loadBusinessProfile,0);
    var navItem=document.querySelector('#nav a[href="#ai"] span:nth-child(2)');
    if(navItem)navItem.textContent="Business & AI";
    var voiceTest=window.TWIGA_AI_TEST_BOX
      ? C("Try your AI receptionist",window.TWIGA_AI_TEST_BOX)
      : C("AI receptionist test",'<div class="pb sub">The voice-test module is not loaded. Refresh the workspace and try again.</div>');
    return [
      "Describe your business once. Your website and Android app use the same saved profile.",
      C("Describe your business",'<div class="pb"><div class="sub" style="margin-bottom:12px">Tell TWIGA what you sell, what you do, who you serve, your prices, opening hours and how customers should be helped. Save once; it is shared with the Android app.</div><div id="twigaBusinessProfile" class="sub">Loading your business profile…</div></div>')+voiceTest
    ];
  };
  window.V=window.V||{};window.V.ai="Business & AI";
  var aiNavItem=document.querySelector('#nav a[href="#ai"] span:nth-child(2)');if(aiNavItem)aiNavItem.textContent="Business & AI";
  document.addEventListener("click",async function(e){
    var b=e.target.closest("[data-profile-save]");if(!b)return;
    b.disabled=true;
    var msg=$("twigaProfileMessage");
    try{
      var row={business_id:twigaBusinessId(),updated_by:twigaUserId(),updated_at:new Date().toISOString()};
      PROFILE_FIELDS.forEach(function(f){row[f[0]]=$("twigaProfile_"+f[0]).value.trim()});
      if(!row.business_summary)throw new Error("Start by describing your business. Include what you do and where customers can find you.");
      row.brand_voice=$("twigaProfile_brand_voice").value.trim();
      row.reply_language=$("twigaProfile_reply_language").value;
      var saved=await twigaDb().from("twiga_business_profiles").upsert(row,{onConflict:"business_id"});
      if(saved.error)throw saved.error;

      // Keep the actual provider prompt in sync with the structured business profile.
      var current=await twigaDb().from("business_ai").select("prompt,greeting,auto_reply,faq,rules").eq("business_id",twigaBusinessId()).maybeSingle();
      if(current.error)throw current.error;
      var old=current.data||{};
      var base=String(old.prompt||"").split("\n\n[TWIGA STRUCTURED BUSINESS PROFILE]\n")[0].trim();
      var profileLines=PROFILE_FIELDS.map(function(f){return f[1]+": "+row[f[0]]}).filter(function(x){return x.split(": ").slice(1).join(": ").trim()!==""});
      profileLines.push("Brand voice: "+row.brand_voice,"Reply language: "+row.reply_language);
      var prompt=(base?base+"\n\n":"")+"[TWIGA STRUCTURED BUSINESS PROFILE]\n"+profileLines.join("\n");
      var baseFaq=String(old.faq||"").split("\n\n[TWIGA STRUCTURED BUSINESS PROFILE FAQ]\n")[0].trim();
      var faq=baseFaq+(row.frequently_asked_questions?"\n\n[TWIGA STRUCTURED BUSINESS PROFILE FAQ]\n"+row.frequently_asked_questions:"");
      var ai=await twigaDb().rpc("save_business_ai",{
        p_business:twigaBusinessId(),
        p_prompt:prompt,
        p_greeting:old.greeting||"",
        p_auto_reply:old.auto_reply||"",
        p_faq:faq,
        p_rules:old.rules||""
      });
      if(ai.error)throw ai.error;
      if(msg)msg.textContent="Saved. Your website and Android app now use this same business description.";
    }catch(err){if(msg)msg.textContent="Could not save business profile: "+err.message}
    finally{b.disabled=false}
  });

  window.R.overview=function(){
    setTimeout(loadTwigaOverview,0);
    return[
      "Live activity from this business only. Every total is calculated from saved calls, orders and appointments.",
      '<div class="stats" id="twigaOverviewStats">'+S("Calls today","—","Loading")+S("Orders today","—","Loading")+S("Collected today","—","Loading")+S("Appointments today","—","Loading")+'</div>'+
      '<div class="g2">'+C("Upcoming appointments",'<div id="twigaOverviewAppointments" class="pb sub">Loading…</div>')+
      C("Recent calls",'<div id="twigaOverviewCalls" class="pb sub">Loading…</div>')+'</div>'
    ];
  };
  async function loadTwigaOverview(){
    if(!$("twigaOverviewStats"))return;
    try{
      var start=new Date();start.setHours(0,0,0,0);
      var results=await Promise.all([
        twigaDb().from("call_events").select("direction,number,state,updated_at").eq("business_id",twigaBusinessId()).gte("updated_at",start.toISOString()).order("updated_at",{ascending:false}).limit(100),
        twigaDb().from("twiga_call_records").select("direction,from_number,to_number,status,started_at,created_at,duration_seconds,summary").eq("business_id",twigaBusinessId()).gte("created_at",start.toISOString()).order("created_at",{ascending:false}).limit(100),
        twigaDb().from("twiga_orders").select("total_amount,payment_status,created_at").eq("business_id",twigaBusinessId()).gte("created_at",start.toISOString()),
        twigaDb().from("twiga_appointments").select("customer_name,phone,scheduled_at,notes,status").eq("business_id",twigaBusinessId()).gte("scheduled_at",start.toISOString()).lt("scheduled_at",new Date(start.getTime()+86400000).toISOString()).order("scheduled_at",{ascending:true}).limit(30)
      ]);
      results.forEach(function(x){if(x.error)throw x.error});
      var phoneCalls=results[0].data||[],aiCalls=results[1].data||[],orders=results[2].data||[],appts=results[3].data||[];
      var revenue=orders.filter(function(x){return x.payment_status==="paid"}).reduce(function(n,x){return n+Number(x.total_amount||0)},0);
      var allCalls=phoneCalls.length+aiCalls.length;
      $("twigaOverviewStats").innerHTML=S("Calls today",allCalls,"Saved SIM and AI call records")+
        S("Orders today",orders.length,"POS, staff and AI orders")+
        S("Collected today",money(revenue),"Orders marked paid")+
        S("Appointments today",appts.length,"Saved appointments");
      $("twigaOverviewAppointments").innerHTML=appts.length?T(["Time","Customer","Service / notes"],appts.slice(0,10).map(function(x){return[
        new Date(x.scheduled_at).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}),
        esc(x.customer_name),esc(x.notes||"Appointment")
      ]}),"No appointments today."):empty("No appointments today","New bookings will appear here once saved.");
      var calls=phoneCalls.map(function(x){return{when:x.updated_at,source:"SIM",direction:x.direction,number:x.number,status:x.state,detail:""}})
        .concat(aiCalls.map(function(x){return{when:x.started_at||x.created_at,source:"AI",direction:x.direction,number:x.from_number||x.to_number,status:x.status,detail:x.summary||""}}))
        .sort(function(a,b){return new Date(b.when)-new Date(a.when)}).slice(0,10);
      $("twigaOverviewCalls").innerHTML=calls.length?T(["Time","Source","Number","Result","Summary"],calls.map(function(x){return[
        new Date(x.when).toLocaleTimeString([],{hour:"2-digit",minute:"2-digit"}),
        esc(x.source),esc(x.number||"Unknown"),esc(x.status||"—"),esc(x.detail||"")
      ]}),"No calls today."):empty("No calls today","SIM and AI call records will appear here after real calls are made.");
    }catch(e){
      if($("twigaOverviewCalls"))$("twigaOverviewCalls").textContent="Could not load overview: "+e.message;
    }
  }

  window.R.reports=function(){
    setTimeout(loadTwigaReports,0);
    return[
      "Reports are calculated from the real order and call records saved by this TWIGA business. No estimates or sample conversion rates are shown.",
      '<div class="stats" id="twigaReportStats">'+S("Orders","—","Loading")+S("Revenue","—","Loading")+S("Paid","—","Loading")+S("Calls","—","Loading")+'</div>'+
      C("Recent business orders",'<div id="twigaReportOrders" class="pb sub">Loading…</div>')+
      C("Call outcomes",'<div id="twigaReportCalls" class="pb sub">Loading…</div>')
    ];
  };
  async function loadTwigaReports(){
    try{
      var since=new Date();since.setDate(since.getDate()-7);
      var [orders,calls,sim]=await Promise.all([
        twigaDb().from("twiga_orders").select("order_number,customer_name,total_amount,payment_status,status,created_at").eq("business_id",twigaBusinessId()).gte("created_at",since.toISOString()).order("created_at",{ascending:false}).limit(200),
        twigaDb().from("twiga_call_records").select("direction,from_number,to_number,status,duration_seconds,summary,created_at").eq("business_id",twigaBusinessId()).gte("created_at",since.toISOString()).order("created_at",{ascending:false}).limit(200),
        twigaDb().from("call_events").select("direction,number,state,updated_at").eq("business_id",twigaBusinessId()).gte("updated_at",since.toISOString()).order("updated_at",{ascending:false}).limit(200)
      ]);
      [orders,calls,sim].forEach(function(x){if(x.error)throw x.error});
      var o=orders.data||[],ai=calls.data||[],phone=sim.data||[];
      var total=o.reduce(function(n,x){return n+Number(x.total_amount||0)},0),paid=o.filter(function(x){return x.payment_status==="paid"}).reduce(function(n,x){return n+Number(x.total_amount||0)},0);
      $("twigaReportStats").innerHTML=S("Orders",o.length,"Last 7 days")+S("Order value",money(total),"Last 7 days")+S("Collected",money(paid),"Marked paid only")+S("Calls",ai.length+phone.length,"SIM and AI records");
      $("twigaReportOrders").innerHTML=o.length?T(["Date","Order","Customer","Total","Payment"],o.slice(0,50).map(function(x){return[
        esc(new Date(x.created_at).toLocaleString()),esc(x.order_number||"—"),esc(x.customer_name),money(x.total_amount),G(esc(x.payment_status||"unpaid"),x.payment_status==="paid"?"ok":"or")
      ]}),"No orders in the last 7 days."):empty("No orders yet","Orders will be recorded here as they are created.");
      var callRows=ai.map(function(x){return[esc(new Date(x.created_at).toLocaleString()),"AI",esc(x.direction),esc(x.status),dur(x.duration_seconds),esc(x.summary||"")]})
        .concat(phone.map(function(x){return[esc(new Date(x.updated_at).toLocaleString()),"SIM",esc(x.direction),esc(x.state), "—", esc(x.number||"")] }));
      $("twigaReportCalls").innerHTML=callRows.length?T(["When","Source","Direction","Status","Duration","Summary / number"],callRows.slice(0,100),"No calls yet."):empty("No call reports yet","Make a real SIM or AI call to populate this report.");
    }catch(e){
      if($("twigaReportOrders"))$("twigaReportOrders").textContent="Could not load reports: "+e.message;
    }
  }

  window.R.followups=function(){
    setTimeout(loadTwigaFollowups,0);
    return[
      "Missed calls, customer requests and unpaid orders that still need attention.",
      '<div class="g2">'+C("Pending team tasks",'<div id="twigaFollowupTasks" class="pb sub">Loading…</div>')+
      C("Unpaid orders",'<div id="twigaFollowupOrders" class="pb sub">Loading…</div>')+
      C("Missed calls",'<div id="twigaFollowupMissed" class="pb sub">Loading…</div>')+'</div>'
    ];
  };
  async function loadTwigaFollowups(){
    try{
      var [tasks,orders,missed]=await Promise.all([
        twigaDb().from("twiga_followups").select("id,customer_name,phone_number,task,channel,due_at,status,consent_status").eq("business_id",twigaBusinessId()).in("status",["pending","due","open"]).order("due_at",{ascending:true}).limit(100),
        twigaDb().from("twiga_orders").select("order_number,customer_name,customer_phone,total_amount,payment_status,created_at").eq("business_id",twigaBusinessId()).neq("payment_status","paid").order("created_at",{ascending:false}).limit(100),
        twigaDb().from("call_events").select("number,state,updated_at").eq("business_id",twigaBusinessId()).eq("state","missed").order("updated_at",{ascending:false}).limit(50)
      ]);
      [tasks,orders,missed].forEach(function(x){if(x.error)throw x.error});
      var t=tasks.data||[],o=orders.data||[],m=missed.data||[];
      $("twigaFollowupTasks").innerHTML=t.length?'<div style="display:grid;gap:8px">'+t.map(function(x){return'<div class="row" style="justify-content:space-between;gap:8px;padding:8px 0;border-bottom:1px solid var(--line)"><div><b>'+esc(x.customer_name||x.phone_number)+'</b><div class="sub">'+esc(x.task)+' · Due '+esc(new Date(x.due_at).toLocaleString())+'</div><div class="sub">'+esc(x.channel)+' · consent '+esc(x.consent_status)+'</div></div><button class="btn sm" data-followup-done="'+esc(x.id)+'">Mark done</button></div>'}).join("")+'</div>':empty("No pending tasks","New follow-ups created from calls will appear here.");
      $("twigaFollowupOrders").innerHTML=o.length?T(["Order","Customer","Balance","Created"],o.map(function(x){return[
        esc(x.order_number||"—"),esc(x.customer_name),money(x.total_amount),esc(new Date(x.created_at).toLocaleString())
      ]}),"No unpaid orders."):empty("No unpaid orders","Orders marked paid will disappear from this list.");
      $("twigaFollowupMissed").innerHTML=m.length?T(["Number","Missed at"],m.map(function(x){return[esc(x.number||"Unknown"),esc(new Date(x.updated_at).toLocaleString())]}),"No missed calls."):empty("No missed calls","Missed SIM calls will appear here after call events are recorded.");
    }catch(e){
      if($("twigaFollowupTasks"))$("twigaFollowupTasks").textContent="Could not load follow-ups: "+e.message;
    }
  }
  document.addEventListener("click",async function(e){
    var b=e.target.closest("[data-followup-done]");if(!b)return;
    b.disabled=true;
    try{
      var result=await twigaDb().from("twiga_followups").update({status:"completed",completed_at:new Date().toISOString(),updated_at:new Date().toISOString()}).eq("id",b.dataset.followupDone).eq("business_id",twigaBusinessId());
      if(result.error)throw result.error;
      await loadTwigaFollowups();
    }catch(err){b.disabled=false;b.textContent=err.message}
  });
  window.render();
  window.route(1);
  window.TWIGA_LIVE_UI_READY = true;
})();
