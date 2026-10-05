import { defineConfig } from "@specra/config";

export default defineConfig({
  schemaVersion: 1,
  name: "Odexa Developer Portal",
  openapi: [
    "./contracts/openapi/gateway.json",
    "./contracts/openapi/catalog.json",
    "./contracts/openapi/inventory.json",
    "./contracts/openapi/order.json",
    "./contracts/openapi/payment.json",
    "./contracts/openapi/payment-simulator.json",
  ],
  docs: "./documentation",
  branding: { accent: "#6d5dfc" },
  environments: {
    local: { label: "Local gateway", baseUrl: "http://localhost:8080" },
  },
  playground: {
    mode: "browser",
    environments: ["local"],
    responseLimitBytes: 1048576,
    timeoutMs: 10000,
  },
  navigation: [
    {
      section: "Start",
      items: ["introduction", "local-quickstart", "authentication"],
    },
    {
      section: "Commerce",
      items: ["checkout", "catalog-inventory", "orders", "payments-refunds"],
    },
    {
      section: "Reliability",
      items: ["events", "webhooks-reconciliation", "errors-concurrency"],
    },
    { api: true, label: "HTTP APIs" },
    { section: "Operate", items: ["playground", "operations", "limitations"] },
  ],
  quality: {
    failOn: "error",
    maxWarnings: 250,
  },
});
