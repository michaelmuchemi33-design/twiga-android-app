window.TWIGA_CONFIG={supabaseUrl:"https://ztyxxikkposfpxsutnfr.supabase.co",supabaseKey:"sb_publishable_Zex31DUxW6bX3LP6Wlos2Q_pMiZ_JeT",mode:"live",vapiKey:"2874d463-b31e-4d5a-84d7-e6cbe0a90b15",ledgerUrl:"https://twiga-api.twiga-ke.workers.dev"};
window.TWIGA_GOOGLE_OAUTH={
  clientId:"674695518944-t6o9srd66n88jg5i6ietar92utiek776.apps.googleusercontent.com",
  scopes:[
    "https://www.googleapis.com/auth/calendar.events.owned",
    "https://www.googleapis.com/auth/contacts.readonly",
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive.file"
  ]
};
document.documentElement.classList.add("pre");
// Load the final visual layer after the page's base styles, without replacing the shared workspace.
(function(){
  var add=function(){
    if(document.getElementById("twiga-polish"))return;
    var link=document.createElement("link");
    link.id="twiga-polish";link.rel="stylesheet";link.href="/twiga-polish.css?v=2";
    document.head.appendChild(link);
    var script=document.createElement("script");
    script.src="/twiga-live-fixes.js?v=2";script.async=false;
    function showLoadError(){
      if(document.getElementById("twiga-live-load-error"))return;
      var box=document.createElement("div");box.id="twiga-live-load-error";
      box.setAttribute("role","alert");
      box.style.cssText="position:fixed;z-index:99999;left:16px;right:16px;top:12px;padding:14px 16px;border:1px solid #f47721;border-radius:12px;background:#fff;color:#171717;box-shadow:0 8px 30px rgba(0,0,0,.15);font:14px/1.5 system-ui";
      box.textContent="TWIGA could not load the live workspace features. Refresh the page. If this continues, contact support@twiga.co.ke. The demo fallback is disabled.";
      document.body.appendChild(box);
    }
    script.onerror=showLoadError;
    script.onload=function(){if(!window.TWIGA_LIVE_UI_READY)showLoadError()};
    document.body.appendChild(script);
  };
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",add,{once:true});else add();
})();
