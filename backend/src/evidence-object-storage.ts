export type SignedUploadAuthorization = {
  signedUrl: string;
};

export interface EvidenceObjectStorage {
  createSignedUpload(objectKey: string): Promise<SignedUploadAuthorization>;
}

const BUCKET_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$/;

function pathForUrl(bucket: string, objectKey: string): string {
  return [bucket, ...objectKey.split("/")]
    .map((segment) => encodeURIComponent(segment))
    .join("/");
}

function storageFault(status: number, body: string): Error {
  const suffix = body.trim().slice(0, 256);
  return new Error(
    suffix.length > 0
      ? `storage signed-upload request failed (${status}): ${suffix}`
      : `storage signed-upload request failed (${status})`,
  );
}

export class SupabaseEvidenceObjectStorage implements EvidenceObjectStorage {
  private readonly storageBaseUrl: string;

  constructor(
    supabaseUrl: string,
    private readonly serviceRoleKey: string,
    private readonly bucket: string,
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    const url = new URL(supabaseUrl);
    if (
      url.protocol !== "https:" &&
      url.hostname !== "127.0.0.1" &&
      url.hostname !== "localhost"
    ) {
      throw new Error("Supabase storage URL must use HTTPS");
    }
    if (!serviceRoleKey.trim()) {
      throw new Error("Supabase service-role key is required for storage signing");
    }
    if (!BUCKET_PATTERN.test(bucket)) {
      throw new Error("invalid evidence storage bucket");
    }
    this.storageBaseUrl = new URL("/storage/v1", url).toString().replace(/\/$/u, "");
  }

  async createSignedUpload(objectKey: string): Promise<SignedUploadAuthorization> {
    if (
      !objectKey ||
      objectKey.startsWith("/") ||
      objectKey.endsWith("/") ||
      objectKey.includes("\\") ||
      objectKey.split("/").some((segment) =>
        segment === "" || segment === "." || segment === ".."
      )
    ) {
      throw new Error("invalid evidence object key");
    }

    const response = await this.fetchImpl(
      `${this.storageBaseUrl}/object/upload/sign/${pathForUrl(this.bucket, objectKey)}`,
      {
        method: "POST",
        headers: {
          apikey: this.serviceRoleKey,
          authorization: `Bearer ${this.serviceRoleKey}`,
          "content-type": "application/json",
        },
        body: "{}",
      },
    );

    if (!response.ok) {
      throw storageFault(response.status, await response.text());
    }

    const payload = await response.json() as Record<string, unknown>;
    if (typeof payload.url !== "string" || payload.url.length === 0) {
      throw new Error("storage signed-upload response is missing url");
    }

    const signedUrl = /^https?:\/\//u.test(payload.url)
      ? new URL(payload.url).toString()
      : new URL(
        this.storageBaseUrl + (payload.url.startsWith("/") ? payload.url : `/${payload.url}`),
      ).toString();

    if (!new URL(signedUrl).searchParams.get("token")) {
      throw new Error("storage signed-upload response is missing token");
    }

    return { signedUrl };
  }
}
