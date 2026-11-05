module.exports = (req, res) => {
  res.status(200).json({ ok: true, app: 'twiga', elevenlabs: Boolean(process.env.ELEVENLABS_API_KEY) });
};
