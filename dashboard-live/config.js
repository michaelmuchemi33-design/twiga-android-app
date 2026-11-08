window.TWIGA_CONFIG={supabaseUrl:"https://ztyxxikkposfpxsutnfr.supabase.co",supabaseKey:"sb_publishable_Zex31DUxW6bX3LP6Wlos2Q_pMiZ_JeT",mode:"live",vapiKey:"2874d463-b31e-4d5a-84d7-e6cbe0a90b15",ledgerUrl:"https://twiga-api.twiga-ke.workers.dev"};document.documentElement.classList.add("pre");
// Load the final visual layer after the page's base styles, without replacing the shared workspace.
(function(){
  var add=function(){
    if(document.getElementById("twiga-polish"))return;
    var link=document.createElement("link");
    link.id="twiga-polish";link.rel="stylesheet";link.href="/twiga-polish.css?v=1";
    document.head.appendChild(link);\n    var script=document.createElement("script");script.src="/twiga-live-fixes.js?v=1";script.async=false;document.body.appendChild(script);
  };
  if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",add,{once:true});else add();
})();
