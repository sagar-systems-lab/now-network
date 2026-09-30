import {
  assertEquals,
  assertThrows,
} from "jsr:@std/assert@1";
import {
  parseCsv,
  summarize,
} from "./summarize-latency.ts";

Deno.test("parseCsv reads metric samples", () => {
  assertEquals(
    parseCsv("metric,value_ms\ncold,100\ncold,200\n"),
    [
      { metric: "cold", valueMs: 100 },
      { metric: "cold", valueMs: 200 },
    ],
  );
});

Deno.test("summarize preserves raw samples and avoids fake tiny-sample tails", () => {
  const result = summarize([
    { metric: "cold", valueMs: 300 },
    { metric: "cold", valueMs: 100 },
    { metric: "cold", valueMs: 200 },
  ]);

  assertEquals(result.cold, {
    n: 3,
    min_ms: 100,
    p50_ms: 200,
    p95_ms: null,
    p99_ms: null,
    max_ms: 300,
    raw_ms: [100, 200, 300],
    percentile_method: "nearest-rank",
  });
});

Deno.test("summarize computes p95 after ten observations", () => {
  const result = summarize(
    Array.from({ length: 10 }, (_, index) => ({
      metric: "cold",
      valueMs: (index + 1) * 10,
    })),
  );

  assertEquals(result.cold.p50_ms, 50);
  assertEquals(result.cold.p95_ms, 100);
  assertEquals(result.cold.p99_ms, null);
});

Deno.test("invalid samples fail closed", () => {
  assertThrows(
    () => summarize([{ metric: "cold", valueMs: -1 }]),
    Error,
    "invalid sample",
  );
});
