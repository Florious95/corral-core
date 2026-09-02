export default function (pi) {
  pi.registerProvider("deterministic", {
    name: "Deterministic",
    baseUrl: "http://127.0.0.1:18889/v1",
    apiKey: "test",
    api: "openai-completions",
    models: [{ id: "deterministic", name: "Deterministic", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 8192, maxTokens: 128 }]
  });
}
