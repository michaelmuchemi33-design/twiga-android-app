import Vapi from "https://esm.sh/@vapi-ai/web@2.7.1";
export const VOICES = [["EXAVITQu4vr4xnSDxMaL", "Sarah"], ["Xb7hH8MSUJpSbSDYk0k2", "Alice"], ["hpp4J3VqNfWAUOO0d1Us", "Bella"], ["cgSgspJ2msm6clMCkdW9", "Jessica"], ["pFZP5JQG7iQjIQuC4Bku", "Lily"]];

export function buildAssistant(o) {
  const sw = o.lang === "sw";
  const biz = (o.name || "this business").slice(0, 80);
  const info = o.prompt && o.prompt.trim() ? o.prompt.trim() : "(The owner has not added business details yet. Be helpful in general and offer to take a message.)";
  const system = "You are the AI receptionist for " + biz + ". Speak in short, warm, natural sentences like a helpful Kenyan receptionist. Reply in the language the caller uses (English or Kiswahili) and follow them if they switch. Use only the business information below. If you do not know something, say so and offer to take a message or connect a person. Never invent prices, stock or availability. For an order or booking, collect the caller's name and phone number, repeat the details back and ask them to confirm. Amounts are in Kenya shillings (KSh).\n\nBUSINESS INFORMATION:\n" + info + (o.faq && o.faq.trim() ? "\n\nQUESTIONS AND ANSWERS (use these answers when asked):\n" + o.faq.trim() : "") + (o.rules && o.rules.trim() ? "\n\nRULES (always follow):\n" + o.rules.trim() : "");
  return {
    name: "TWIGA receptionist",
    firstMessage: (o.greeting && o.greeting.trim()) || (sw ? "Habari! Karibu " + biz + ". Naweza kukusaidiaje leo?" : "[cheerfully] Hello! Karibu " + biz + ". How can I help you today?"),
    model: { provider: "openai", model: "gpt-4o", temperature: 0.5, messages: [{ role: "system", content: system }] },
    voice: { provider: "11labs", voiceId: o.voice || VOICES[0][0], model: sw ? "eleven_multilingual_v2" : "eleven_v3" },
    transcriber: sw ? { provider: "gladia", language: "sw" } : { provider: "deepgram", model: "nova-2", language: "en" },
    maxDurationSeconds: o.maxSeconds || 300,
    backgroundDenoisingEnabled: true
  };
}

function why(e) { return (e && (e.errorMsg || e.message || (e.error && e.error.message))) || "Something went wrong. Allow the microphone and try again."; }

export function createCall(publicKey, h) {
  let vapi = null, live = false;
  return {
    get live() { return live; },
    async start(o) {
      h.state("connecting");
      try {
        vapi = new Vapi(publicKey);
        vapi.on("call-start", () => { live = true; h.state("listening"); });
        vapi.on("call-end", () => { live = false; h.state("ended"); });
        vapi.on("speech-start", () => h.state("speaking"));
        vapi.on("speech-end", () => h.state("listening"));
        vapi.on("message", m => { if (m && m.type === "transcript" && (!m.transcriptType || m.transcriptType === "final")) h.line(m.role, m.transcript); });
        vapi.on("error", e => { live = false; h.error(why(e)); });
        await vapi.start(buildAssistant(o));
      } catch (e) { live = false; h.error(why(e)); }
    },
    stop() { try { vapi && vapi.stop(); } catch (e) {} }
  };
}
