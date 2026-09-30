type Sample = {
  metric: string;
  valueMs: number;
};

type Summary = {
  n: number;
  min_ms: number;
  p50_ms: number;
  p95_ms: number | null;
  p99_ms: number | null;
  max_ms: number;
  raw_ms: number[];
  percentile_method: "nearest-rank";
};

function percentile(sorted: number[], probability: number): number {
  const rank = Math.max(1, Math.ceil(probability * sorted.length));
  return sorted[Math.min(rank - 1, sorted.length - 1)];
}

export function summarize(samples: Sample[]): Record<string, Summary> {
  const grouped = new Map<string, number[]>();

  for (const sample of samples) {
    if (!Number.isFinite(sample.valueMs) || sample.valueMs < 0) {
      throw new Error(`invalid sample for ${sample.metric}: ${sample.valueMs}`);
    }

    const values = grouped.get(sample.metric) ?? [];
    values.push(sample.valueMs);
    grouped.set(sample.metric, values);
  }

  const result: Record<string, Summary> = {};

  for (const [metric, values] of [...grouped.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const sorted = [...values].sort((a, b) => a - b);
    result[metric] = {
      n: sorted.length,
      min_ms: sorted[0],
      p50_ms: percentile(sorted, 0.50),
      p95_ms: sorted.length >= 10 ? percentile(sorted, 0.95) : null,
      p99_ms: sorted.length >= 20 ? percentile(sorted, 0.99) : null,
      max_ms: sorted[sorted.length - 1],
      raw_ms: sorted,
      percentile_method: "nearest-rank",
    };
  }

  return result;
}

export function parseCsv(text: string): Sample[] {
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);

  if (lines.length === 0) return [];

  const header = lines.shift();
  if (header !== "metric,value_ms") {
    throw new Error("expected CSV header: metric,value_ms");
  }

  return lines.map((line, index) => {
    const comma = line.indexOf(",");
    if (comma <= 0 || comma === line.length - 1) {
      throw new Error(`invalid CSV row ${index + 2}`);
    }

    const metric = line.slice(0, comma).trim();
    const valueMs = Number(line.slice(comma + 1).trim());

    if (!metric) throw new Error(`empty metric at row ${index + 2}`);

    return { metric, valueMs };
  });
}

if (import.meta.main) {
  const inputIndex = Deno.args.indexOf("--input");
  const outputIndex = Deno.args.indexOf("--output");

  if (inputIndex < 0 || !Deno.args[inputIndex + 1]) {
    console.error(
      "usage: deno run --allow-read --allow-write scripts/performance/summarize-latency.ts --input samples.csv [--output summary.json]",
    );
    Deno.exit(2);
  }

  const input = Deno.args[inputIndex + 1];
  const samples = parseCsv(await Deno.readTextFile(input));
  const payload = {
    generated_at: new Date().toISOString(),
    source: input,
    summaries: summarize(samples),
  };

  const json = JSON.stringify(payload, null, 2) + "\n";

  if (outputIndex >= 0 && Deno.args[outputIndex + 1]) {
    await Deno.writeTextFile(Deno.args[outputIndex + 1], json);
  } else {
    await Deno.stdout.write(new TextEncoder().encode(json));
  }
}
