import Vapi from "https://esm.sh/@vapi-ai/web@2.7.1";

export const VOICES = [
  ["EXAVITQu4vr4xnSDxMaL", "Sarah"],
  ["Xb7hH8MSUJpSbSDYk0k2", "Alice"],
  ["hpp4J3VqNfWAUOO0d1Us", "Bella"],
  ["cgSgspJ2msm6clMCkdW9", "Jessica"],
  ["pFZP5JQG7iQjIQuC4Bku", "Lily"]
];

const BASE_PROMPT = `You are TWIGA, a fast, capable AI receptionist for a Kenyan business. Speak naturally and warmly, like a helpful professional receptionist.

LANGUAGE
Understand and respond naturally in English and Kiswahili. Detect the caller's language from their speech and reply in that language. If they mix English and Kiswahili, naturally code-switch. Do not translate aloud. Use clear Kenyan English and natural Kiswahili; avoid forced slang. Ask one question at a time, let callers finish, and do not repeat information already given.

TASKS
Welcome callers, understand requests, answer from the business context supplied for this call, take messages, capture leads, assist with orders, and handle appointment requests. Collect only relevant details: name, callback number, request, order items and quantities, or preferred appointment date/time. Read back important names, numbers, quantities, dates and totals for confirmation.

Never claim an order, booking, payment, refund, stock check, transfer, or message is completed unless a connected tool confirms success. If no relevant tool is available, explain that you can record the request for the business to confirm. Never invent prices, stock, opening hours, policies, availability, order status, delivery promises, or business facts. If information is missing, say so and offer to take a message. If the caller asks for a human or callback, collect the request and use an available handoff/message tool.

BUSINESS CONTEXT
The business name, custom greeting, services, products, prices, hours, policies, FAQs and owner instructions may be supplied dynamically for each call. Treat them as the source of truth for this call only. Follow the custom greeting exactly when supplied. Never reveal another business's information or assume context from a previous call. If context is absent, say you are the business's virtual receptionist and offer to take a message.

PRIVACY
Never request passwords, PINs, OTPs, full card numbers or unnecessary sensitive information. Do not disclose private customer or business records unless the caller is appropriately verified and a tool permits it. Do not make commitments outside the supplied instructions.

FAST CONVERSATION
Respond as soon as the caller finishes a thought. Avoid filler, long explanations, repeated summaries and unnecessary preambles. Usually answer in one or two concise sentences, then ask the next useful question. Do not announce internal reasoning or say you are searching unless a real tool operation is underway. Handle complex requests one step at a time. Briefly confirm the request and next step before closing.`;

function makeContext(o = {}) {
  const business = String(o.name || "this business").slice(0, 100);
  const details = [
    "BUSINESS NAME: " + business,
    o.prompt ? "BUSINESS INFORMATION AND OWNER INSTRUCTIONS:\n" + o.prompt : "",
    o.faq ? "FAQ:\n" + o.faq : "",
    o.rules ? "BUSINESS RULES:\n" + o.rules : ""
  ].filter(Boolean).join("\n\n");
  return BASE_PROMPT + "\n\nCURRENT CALL CONTEXT (use only for this call):\n" + details;
}

function errorText(e) {
  return (e && (e.errorMsg || e.message || (e.error && e.error.message))) ||
    "Could not start the call. Check microphone permission and try again.";
}

export function createCall(publicKey, assistantIdOrHooks, hooksMaybe = {}) {
  // Support both the explicit signature and the older dashboard call signature.
  const assistantId = typeof assistantIdOrHooks === "string"
    ? assistantIdOrHooks
    : ((window.TWIGA_CONFIG || {}).vapiAssistantId || "");
  const hooks = (assistantIdOrHooks && typeof assistantIdOrHooks === "object")
    ? assistantIdOrHooks
    : hooksMaybe;

  let vapi = null;
  let live = false;
  let starting = false;
  let ended = false;
  let requestedAt = 0;
  let callStartedAt = 0;
  let pendingUserAt = 0;
  let firstResponseMs = null;
  let lastResponseMs = null;
  let connectMs = null;
  let turnCount = 0;
  let callId = null;
  let startedAt = null;

  const emit = (name, ...args) => { try { hooks[name]?.(...args); } catch (_) {} };
  const now = () => (typeof performance !== "undefined" ? performance.now() : Date.now());
  const snapshot = () => ({ connectMs, firstResponseMs, lastResponseMs, turnCount });
  const reportEnd = () => {
    if (ended) return;
    ended = true;
    const endedAt = new Date().toISOString();
    const durationSeconds = startedAt
      ? Math.max(0, Math.round((Date.parse(endedAt) - Date.parse(startedAt)) / 1000))
      : 0;
    emit("end", {
      callId,
      startedAt,
      endedAt,
      durationSeconds,
      metrics: snapshot()
    });
  };

  return {
    get live() { return live; },
    get connecting() { return starting; },
    getMetrics() { return snapshot(); },

    async start(options = {}) {
      if (live || starting) return;
      if (!publicKey || !assistantId) {
        emit("error", "TWIGA voice configuration is incomplete. Refresh and try again.");
        return;
      }

      starting = true;
      ended = false;
      requestedAt = now();
      pendingUserAt = 0;
      firstResponseMs = null;
      lastResponseMs = null;
      connectMs = null;
      turnCount = 0;
      callId = null;
      startedAt = null;
      emit("state", "connecting");
      emit("metric", snapshot());

      try {
        vapi = new Vapi(publicKey);
        vapi.on("call-start", event => {
          callStartedAt = now();
          startedAt = new Date().toISOString();
          callId = event?.call?.id || event?.id || null;
          live = true;
          starting = false;
          connectMs = Math.max(0, Math.round(callStartedAt - requestedAt));
          emit("call", { callId, startedAt });
          emit("metric", snapshot());
          emit("state", "listening");
        });

        vapi.on("call-end", () => {
          live = false;
          starting = false;
          reportEnd();
          emit("state", "ended");
        });

        vapi.on("speech-start", () => emit("state", "speaking"));
        vapi.on("speech-end", () => emit("state", "listening"));

        vapi.on("message", message => {
          if (message?.type !== "transcript" || !message.transcript) return;
          if (message.transcriptType && message.transcriptType !== "final") return;

          const at = now();
          const role = message.role === "user" ? "user" : "assistant";
          if (role === "user") {
            pendingUserAt = at;
          } else {
            if (firstResponseMs === null && callStartedAt) {
              firstResponseMs = Math.max(0, Math.round(at - callStartedAt));
            }
            if (pendingUserAt) {
              lastResponseMs = Math.max(0, Math.round(at - pendingUserAt));
              pendingUserAt = 0;
            }
            turnCount += 1;
          }

          emit("line", role, message.transcript);
          emit("metric", snapshot());
        });

        vapi.on("error", error => {
          live = false;
          starting = false;
          emit("error", errorText(error));
          reportEnd();
          emit("state", "ended");
        });

        const assistantOverrides = {
          firstMessage: String(options.greeting || "").trim() ||
            "[cheerfully] Hello! Thank you for calling. How can I help you today?",
          model: {
            provider: "openai",
            model: "gpt-4o",
            temperature: 0.3,
            messages: [{ role: "system", content: makeContext(options) }]
          },
          voice: {
            provider: "11labs",
            voiceId: options.voice || VOICES[0][0],
            model: "eleven_v3"
          },
          startSpeakingPlan: {
            waitSeconds: 0.15,
            smartEndpointingPlan: { provider: "livekit" }
          },
          stopSpeakingPlan: {
            numWords: 0,
            voiceSeconds: 0.2,
            backoffSeconds: 0.5
          },
        };

        if (Number.isFinite(Number(options.maxSeconds)) && Number(options.maxSeconds) > 0) {
          assistantOverrides.maxDurationSeconds = Number(options.maxSeconds);
        }

        await vapi.start(assistantId, assistantOverrides);
      } catch (error) {
        live = false;
        starting = false;
        emit("error", "Vapi start failed: " + errorText(error));
        reportEnd();
        emit("state", "ended");
      }
    },

    stop() {
      try {
        if (vapi && (live || starting)) vapi.stop();
      } catch (_) {}
      live = false;
      starting = false;
      reportEnd();
      emit("state", "ended");
    }
  };
}
