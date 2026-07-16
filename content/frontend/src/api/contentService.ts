import axios from "axios";
import { refreshBrowserSession } from "@/api/client";
import { redirectToAccount } from "@/api/authRedirect";
import { runtimeConfig } from "@/runtime-config";
import type {
  CommentItem,
  CommentSort,
  CommentThreadInput,
  CommentThreadResponse,
  CommentReactionState,
  ContentBlock, CommentDocumentV1,
  CollectionDetail,
  CreateCollectionInput,
  CreatePostInput,
  CreateStoryInput,
  CurrentActor,
  CompletedMediaAssetPart,
  FeedItem,
  InitMediaAssetUploadInput,
  MediaAssetUploadPart,
  MediaAssetUploadSession,
  PostCollectionsState,
  PostAsset,
  PostEditorDocument,
  EditorMediaAssetResult,
  SavePostEditorDocumentInput,
  RecommendationFeedInput,
  RecommendationFeedResponse,
  SavedCollection,
  PostReactionState,
  PollVoteState,
  SavePostDraftInput,
  Story,
  StoryArchiveResponse,
  StoryArchivePeriodsResponse,
  StoryBlock,
  StoryGroup,
  StoryRailItem,
  StoryReactionState,
  UpdateCommentInput,
  UpdatePostInput,
} from "@/api/types";

/** Keep regular multipart parts safely above S3's 5 MiB lower bound. */
export const MEDIA_UPLOAD_CHUNK_BYTES = 8 * 1024 * 1024;

export interface UploadMediaAssetOptions {
  signal?: AbortSignal;
  sourcePolicyId?: "browser-native-v1" | "browser-capture-v1";
  /** Called as soon as Content has reserved an asset id for this browser file. */
  onReserved?: (asset: PostAsset) => void;
  /** 0..1 progress through the direct PUTs. */
  onProgress?: (progress: number) => void;
}

export interface PollMediaAssetOptions {
  signal?: AbortSignal;
  onUpdate?: (asset: PostAsset) => void;
  /** Primarily useful for tests and deliberately bounded to prevent a runaway tab. */
  maxAttempts?: number;
}

const graphql = axios.create({
  baseURL: runtimeConfig.graphqlUrl,
  timeout: 12000,
  withCredentials: true,
});

const profileApi = axios.create({
  baseURL: new URL("/api", runtimeConfig.profileFrontendUrl).toString().replace(/\/$/, ""),
  timeout: 9000,
  withCredentials: true,
});

graphql.interceptors.request.use(async (config) => {
  config.headers.set("X-Onix-Redirect", window.location.href);
  return config;
});

profileApi.interceptors.request.use(async (config) => {
  config.headers.set("X-Onix-Redirect", window.location.href);
  return config;
});

graphql.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401 && isOptionalAuthRequest(error.config)) {
      return Promise.reject(error);
    }
    if (error.response?.status === 401) {
      const config = error.config as (typeof error.config & { _sessionRetry?: boolean }) | undefined;
      if (config && !config._sessionRetry) {
        config._sessionRetry = true;
        try {
          await refreshBrowserSession();
          return graphql.request(config);
        } catch {
          // Fall through to Account redirect.
        }
      }
      redirectToAccount();
      return new Promise(() => undefined);
    }
    return Promise.reject(error);
  },
);

interface GraphQlResponse<T> {
  data?: T;
  errors?: Array<{ message: string }>;
}

interface CollectionItemRef {
  serviceKey: string;
  itemType: string;
  itemId: string;
}

interface ItemCollectionsState {
  ref: CollectionItemRef;
  collectionIds: string[];
}

async function request<T>(operationName: string, variables: Record<string, unknown> = {}, options: { optionalAuth?: boolean } = {}): Promise<T> {
  const response = await graphql.post<GraphQlResponse<T>>("", {
    operationName,
    variables,
  }, {
    headers: options.optionalAuth ? { "X-Onix-Optional-Auth": "1" } : undefined,
  }).catch((error: unknown) => {
    throw graphQlRequestError(error);
  });
  const error = response.data.errors?.[0]?.message;
  if (error) throw new Error(error);
  if (!response.data.data) throw new Error("Empty content response");
  return response.data.data;
}

function blockText(blocks: ContentBlock[]): string {
  return blocks.map((block) => {
    const value = block.data.text;
    return typeof value === "string" ? value : "";
  }).filter(Boolean).join("\n");
}

function valueAsString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

async function multipartRequest<T>(
  operationName: string,
  variables: Record<string, unknown>,
  files: File[],
): Promise<T> {
  const form = new FormData();
  form.set("operations", JSON.stringify({ operationName, variables }));
  form.set(
    "map",
    JSON.stringify(Object.fromEntries(files.map((_, index) => [String(index), [`variables.files.${index}`]]))),
  );
  files.forEach((file, index) => form.set(String(index), file, file.name));
  const response = await graphql.post<GraphQlResponse<T>>("", form).catch((error: unknown) => {
    throw graphQlRequestError(error);
  });
  const error = response.data.errors?.[0]?.message;
  if (error) throw new Error(error);
  if (!response.data.data) throw new Error("Empty content response");
  return response.data.data;
}

function graphQlRequestError(error: unknown): Error {
  if (axios.isAxiosError<GraphQlResponse<unknown>>(error)) {
    const message = error.response?.data?.errors?.[0]?.message;
    if (message) return new Error(message);
  }
  return error instanceof Error ? error : new Error("Content request failed");
}

function isOptionalAuthRequest(config?: { headers?: unknown } | null): boolean {
  const headers = config?.headers as { get?: (name: string) => unknown; [key: string]: unknown } | undefined;
  return headers?.get?.("X-Onix-Optional-Auth") === "1" || headers?.["X-Onix-Optional-Auth"] === "1";
}

function apiRequestError(error: unknown): Error {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { message?: string; code?: string; errors?: Array<{ message?: string }> } | undefined;
    const message = data?.message || data?.errors?.[0]?.message || data?.code;
    if (message) return new Error(message);
  }
  return error instanceof Error ? error : new Error("API request failed");
}

function itemCollectionsPath(ref: CollectionItemRef): string {
  return `/items/${encodeURIComponent(ref.serviceKey)}/${encodeURIComponent(ref.itemType)}/${encodeURIComponent(ref.itemId)}/collections`;
}

/** Browser object URLs are editor-only and must never reach the public API. */
function serializeMediaInput<T extends { assets?: PostAsset[] }>(input: T): T {
  if (!input.assets) return input;
  return {
    ...input,
    assets: input.assets.map(({ previewUrl: _previewUrl, ...asset }) => asset),
  };
}

/** Comment media uses the same asset contract, under the backend's explicit
 * `attachments` field. Browser preview URLs are never persisted. */
function serializeCommentMediaInput<T extends { attachments?: PostAsset[] }>(input: T): T {
  if (!input.attachments) return input;
  return {
    ...input,
    attachments: input.attachments.map(({ previewUrl: _previewUrl, ...asset }) => asset),
  };
}

type RawMediaAsset = PostAsset & {
  deliveryVariants?: PostAsset["variants"];
  poster?: { url?: string | null } | null;
  waveform?: { url?: string | null } | null;
};

function isMediaAssetStatus(value: unknown): value is PostAsset["status"] {
  return value === "UPLOADING" || value === "PROCESSING" || value === "READY" || value === "FAILED";
}

/**
 * MediaStore returns an asset record, whereas a post keeps a separate item id.
 * Preserve the editor's local id so reordering/removal cannot race an upload,
 * while copying the server asset id and delivery information into the post asset.
 */
function mergeUploadedAsset(remote: PostAsset, local?: PostAsset): PostAsset {
  const raw = remote as RawMediaAsset;
  const variants = raw.variants || raw.deliveryVariants || local?.variants;
  const assetId = raw.assetId || raw.id || local?.assetId || null;
  const posterUrl = raw.posterUrl || raw.poster?.url || local?.posterUrl || null;
  const waveformUrl = raw.waveformUrl || raw.waveform?.url || local?.waveformUrl || null;
  const status = isMediaAssetStatus(raw.status) ? raw.status : local?.status || "PROCESSING";
  const firstDelivery = variants?.find((variant) => Boolean(variant.url))?.url || null;

  return {
    ...local,
    ...remote,
    id: local?.id || raw.id || assetId || crypto.randomUUID(),
    clientId: local?.clientId,
    kind: raw.kind || local?.kind || "IMAGE",
    sourceKind: raw.sourceKind || local?.sourceKind || "UPLOAD",
    assetId,
    status,
    variants,
    url: raw.url || firstDelivery || local?.url || null,
    posterUrl,
    waveformUrl,
    previewUrl: local?.previewUrl || raw.previewUrl || null,
  };
}

function throwIfAborted(signal?: AbortSignal) {
  if (!signal?.aborted) return;
  throw new DOMException("Загрузка отменена", "AbortError");
}

function sleep(milliseconds: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException("Загрузка отменена", "AbortError"));
      return;
    }
    const timeout = window.setTimeout(resolve, milliseconds);
    signal?.addEventListener("abort", () => {
      window.clearTimeout(timeout);
      reject(new DOMException("Загрузка отменена", "AbortError"));
    }, { once: true });
  });
}

async function putMediaPart(part: MediaAssetUploadPart, body: Blob, mimeType: string, signal?: AbortSignal): Promise<CompletedMediaAssetPart> {
  throwIfAborted(signal);
  const headers = new Headers();
  for (const [name, value] of Object.entries(part.headers || {})) {
    if (typeof value === "string" && value) headers.set(name, value);
  }
  // Some stores sign Content-Type and provide it above; the fallback keeps
  // simpler presigned endpoints type-aware without ever forwarding cookies.
  if (!headers.has("content-type") && mimeType) headers.set("content-type", mimeType);

  let response: Response;
  try {
    response = await fetch(part.url, {
      method: "PUT",
      headers,
      body,
      credentials: "omit",
      signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new Error("Не удалось подключиться к хранилищу медиа. Перезапустите локальный dev-стек и повторите загрузку.");
  }
  if (!response.ok) {
    throw new Error(`Хранилище отклонило загрузку части ${part.partNumber} (HTTP ${response.status}).`);
  }
  const etag = response.headers.get("etag");
  // Multipart completion is impossible without every ETag. Surface a precise
  // deployment error instead of persisting an upload that MediaStore cannot
  // finalize; the bucket CORS policy must expose this response header.
  if (!etag) throw new Error("Хранилище не вернуло ETag части. Проверьте CORS для загрузки медиа.");
  return { partNumber: part.partNumber, etag };
}

export class ContentService {
  static async currentActor(): Promise<CurrentActor> {
    const data = await request<{ currentActor: CurrentActor }>("currentActor", {}, { optionalAuth: true });
    return data.currentActor;
  }

  static async recommendationFeed(input: RecommendationFeedInput): Promise<RecommendationFeedResponse> {
    const data = await request<{ recommendationFeed: RecommendationFeedResponse }>("recommendationFeed", { input });
    return data.recommendationFeed;
  }

  static async feed(tags: string[] = [], limit = 30): Promise<FeedItem[]> {
    const data = await request<{ feed: FeedItem[] }>("feed", { tags, limit });
    return data.feed;
  }

  static async collections(ownerId: string, ownerType: "USER" | "ORGANIZATION" = "USER", limit = 80): Promise<SavedCollection[]> {
    const response = await profileApi.get<SavedCollection[]>(`/owners/${ownerType}/${encodeURIComponent(ownerId)}/collections`, { params: { limit } })
      .catch((error: unknown) => {
        throw apiRequestError(error);
      });
    return response.data;
  }

  static async collection(id: string, limit = 200): Promise<CollectionDetail> {
    const data = await request<{ collection: CollectionDetail }>("collection", { id, limit });
    return data.collection;
  }

  static async createCollection(input: CreateCollectionInput): Promise<SavedCollection> {
    const data = await request<{ createCollection: SavedCollection }>("createCollection", { input });
    return data.createCollection;
  }

  static async postCollections(postId: string): Promise<PostCollectionsState> {
    const state = await ContentService.itemCollections({ serviceKey: "content", itemType: "post", itemId: postId });
    return { postId, collectionIds: state.collectionIds };
  }

  static async setPostCollections(postId: string, collectionIds: string[]): Promise<PostCollectionsState> {
    const state = await ContentService.setItemCollections({ serviceKey: "content", itemType: "post", itemId: postId }, collectionIds);
    return { postId, collectionIds: state.collectionIds };
  }

  static async itemCollections(ref: CollectionItemRef): Promise<ItemCollectionsState> {
    const response = await profileApi.get<ItemCollectionsState>(itemCollectionsPath(ref)).catch((error: unknown) => {
      throw apiRequestError(error);
    });
    return response.data;
  }

  static async setItemCollections(ref: CollectionItemRef, collectionIds: string[]): Promise<ItemCollectionsState> {
    const response = await profileApi.put<ItemCollectionsState>(itemCollectionsPath(ref), { collectionIds }).catch((error: unknown) => {
      throw apiRequestError(error);
    });
    return response.data;
  }

  static async createPost(input: CreatePostInput, files: File[] = []): Promise<FeedItem["post"]> {
    const mediaInput = serializeMediaInput(input);
    const data = files.length
      ? await multipartRequest<{ createPost: FeedItem["post"] }>("createPost", { input: mediaInput }, files)
      : await request<{ createPost: FeedItem["post"] }>("createPost", { input: mediaInput });
    return data.createPost;
  }

  static async updatePost(input: UpdatePostInput, files: File[] = []): Promise<FeedItem["post"]> {
    const mediaInput = serializeMediaInput(input);
    const data = files.length
      ? await multipartRequest<{ updatePost: FeedItem["post"] }>("updatePost", { input: mediaInput }, files)
      : await request<{ updatePost: FeedItem["post"] }>("updatePost", { input: mediaInput });
    return data.updatePost;
  }

  static async savePostDraft(input: SavePostDraftInput, files: File[] = []): Promise<FeedItem["post"]> {
    const mediaInput = serializeMediaInput(input);
    const data = files.length
      ? await multipartRequest<{ savePostDraft: FeedItem["post"] }>("savePostDraft", { input: mediaInput }, files)
      : await request<{ savePostDraft: FeedItem["post"] }>("savePostDraft", { input: mediaInput });
    return data.savePostDraft;
  }

  static async beginPostEdit(postId: string): Promise<PostEditorDocument> {
    const data = await request<{ beginPostEdit: PostEditorDocument }>("beginPostEdit", { postId });
    return data.beginPostEdit;
  }

  static async createPostDraft(): Promise<PostEditorDocument> {
    const data = await request<{ createPostDraft: PostEditorDocument }>("createPostDraft", {});
    return data.createPostDraft;
  }

  static async postEditorDocument(revisionId: string): Promise<PostEditorDocument> {
    const data = await request<{ postEditorDocument: PostEditorDocument }>("postEditorDocument", { revisionId });
    return data.postEditorDocument;
  }

  static async savePostEditorDocument(input: SavePostEditorDocumentInput): Promise<PostEditorDocument> {
    const data = await request<{ savePostEditorDocument: PostEditorDocument }>("savePostEditorDocument", {
      input: serializeMediaInput(input),
    });
    return data.savePostEditorDocument;
  }

  static async requestPostRevisionPublication(revisionId: string, idempotencyKey: string): Promise<import("@/api/types").PostPublication> {
    const data = await request<{ requestPostRevisionPublication: import("@/api/types").PostPublication }>(
      "requestPostRevisionPublication",
      { revisionId, idempotencyKey },
    );
    return data.requestPostRevisionPublication;
  }

  static async postDrafts(limit = 40): Promise<FeedItem["post"][]> {
    const data = await request<{ postDrafts: FeedItem["post"][] }>("postDrafts", { limit });
    return data.postDrafts;
  }

  static async postDraft(draftId: string): Promise<FeedItem["post"]> {
    const data = await request<{ postDraft: FeedItem["post"] }>("postDraft", { draftId });
    return data.postDraft;
  }

  static async publishPostDraft(draftId: string): Promise<FeedItem["post"]> {
    const data = await request<{ publishPostDraft: FeedItem["post"] }>("publishPostDraft", { draftId });
    return data.publishPostDraft;
  }

  static async requestPostPublication(draftId: string, idempotencyKey: string): Promise<import("@/api/types").PostPublication> {
    const data = await request<{ requestPostPublication: import("@/api/types").PostPublication }>("requestPostPublication", { draftId, idempotencyKey });
    return data.requestPostPublication;
  }

  static async postPublication(draftId: string): Promise<import("@/api/types").PostPublication> {
    const data = await request<{ postPublication: import("@/api/types").PostPublication }>("postPublication", { draftId });
    return data.postPublication;
  }

  static async cancelPostPublication(draftId: string): Promise<import("@/api/types").PostPublication> {
    const data = await request<{ cancelPostPublication: import("@/api/types").PostPublication }>("cancelPostPublication", { draftId });
    return data.cancelPostPublication;
  }

  static async deletePost(id: string): Promise<boolean> {
    const data = await request<{ deletePost: boolean }>("deletePost", { id });
    return data.deletePost;
  }

  static async post(id: string): Promise<FeedItem["post"] | null> {
    const data = await request<{ post: FeedItem["post"] | null }>("post", { id });
    return data.post;
  }

  static async comment(id: string): Promise<CommentItem | null> {
    const data = await request<{ comment: CommentItem | null }>("comment", { id });
    return data.comment;
  }

  static async initMediaAssetUpload(input: InitMediaAssetUploadInput): Promise<MediaAssetUploadSession> {
    const data = await request<{ initMediaAssetUpload: MediaAssetUploadSession }>("initMediaAssetUpload", { input });
    if (!data.initMediaAssetUpload.sessionId || !data.initMediaAssetUpload.parts?.length) {
      throw new Error("Сервис медиа не вернул адреса загрузки.");
    }
    return data.initMediaAssetUpload;
  }

  static async completeMediaAssetUpload(input: {
    assetId: string;
    sessionId: string;
    parts: CompletedMediaAssetPart[];
  }): Promise<PostAsset> {
    const data = await request<{ completeMediaAssetUpload: PostAsset }>("completeMediaAssetUpload", { input });
    return data.completeMediaAssetUpload;
  }

  static async mediaAsset(assetId: string): Promise<PostAsset> {
    const data = await request<{ mediaAsset: PostAsset }>("mediaAsset", { assetId });
    return data.mediaAsset;
  }

  static async editorMediaAssets(assetIds: string[]): Promise<EditorMediaAssetResult[]> {
    if (!assetIds.length) return [];
    const data = await request<{ editorMediaAssets: EditorMediaAssetResult[] }>("editorMediaAssets", { assetIds: [...new Set(assetIds)].slice(0, 12) });
    return data.editorMediaAssets;
  }

  static async retryMediaAssetProcessing(assetId: string): Promise<PostAsset> {
    const data = await request<{ retryMediaAssetProcessing: PostAsset }>("retryMediaAssetProcessing", { assetId });
    return data.retryMediaAssetProcessing;
  }

  /**
   * Uploads a local file directly to the signed MediaStore URLs. The Content
   * API only brokers a short-lived session and never receives the original
   * bytes, so a post can later reference the immutable `assetId`.
   */
  static async uploadMediaAsset(file: File, local?: PostAsset, options: UploadMediaAssetOptions = {}): Promise<PostAsset> {
    if (!file.size) throw new Error("Пустой файл нельзя загрузить.");
    const kind = local?.kind || (file.type.startsWith("video/") ? "VIDEO" : file.type.startsWith("audio/") ? "AUDIO" : "IMAGE");
    const partsCount = Math.max(1, Math.ceil(file.size / MEDIA_UPLOAD_CHUNK_BYTES));
    const session = await ContentService.initMediaAssetUpload({
      mimeType: file.type || "application/octet-stream",
      expectedSize: file.size,
      partsCount,
      kind,
      sourcePolicyId: options.sourcePolicyId || "browser-native-v1",
    });
    const reserved = mergeUploadedAsset({ ...session.asset, status: "UPLOADING" }, local);
    options.onReserved?.(reserved);

    if (session.parts.length !== partsCount) {
      throw new Error("Сервис медиа вернул неполный сеанс загрузки.");
    }

    const completed: CompletedMediaAssetPart[] = [];
    for (let index = 0; index < session.parts.length; index += 1) {
      throwIfAborted(options.signal);
      const part = session.parts[index];
      const start = index * MEDIA_UPLOAD_CHUNK_BYTES;
      const body = file.slice(start, Math.min(file.size, start + MEDIA_UPLOAD_CHUNK_BYTES));
      completed.push(await putMediaPart(part, body, file.type || "application/octet-stream", options.signal));
      options.onProgress?.((index + 1) / session.parts.length);
    }

    const completedAsset = await ContentService.completeMediaAssetUpload({
      assetId: reserved.assetId || session.asset.assetId || session.asset.id,
      sessionId: session.sessionId,
      parts: completed,
    });
    return mergeUploadedAsset(completedAsset, reserved);
  }

  /** Polls a processing asset only until MediaStore reaches a terminal state. */
  static async waitForMediaAsset(assetId: string, options: PollMediaAssetOptions = {}): Promise<PostAsset> {
    const maxAttempts = Math.max(1, options.maxAttempts ?? 120);
    let last: PostAsset | null = null;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      throwIfAborted(options.signal);
      const asset = await ContentService.mediaAsset(assetId);
      last = asset;
      options.onUpdate?.(asset);
      if (asset.status === "AVAILABLE" || asset.status === "READY" || asset.status === "FAILED" || asset.status === "CANCELLED") return asset;
      // Quick feedback after completion, then a capped cadence while codecs run.
      await sleep(Math.min(4000, 700 + attempt * 160), options.signal);
    }
    throw new Error(last?.status === "VERIFYING" ? "Проверка файла занимает больше обычного." : "Не удалось получить статус медиа.");
  }

  static async uploadPostMedia(file: File): Promise<StoryBlock> {
    const type = file.type.startsWith("video/")
      ? "VIDEO"
      : file.type.startsWith("audio/")
        ? "AUDIO"
        : "IMAGE";
    return {
      id: crypto.randomUUID(),
      type,
      data: {
        fileName: file.name,
        mimeType: file.type,
        size: file.size,
        previewUrl: URL.createObjectURL(file),
      },
    };
  }

  static async likePost(postId: string): Promise<PostReactionState> {
    const data = await request<{ likePost: PostReactionState }>("likePost", { postId });
    return data.likePost;
  }

  static async unlikePost(postId: string): Promise<PostReactionState> {
    const data = await request<{ unlikePost: PostReactionState }>("unlikePost", { postId });
    return data.unlikePost;
  }

  static async recordView(postId: string, durationMs = 0): Promise<boolean> {
    const data = await request<{ recordView: boolean }>("recordView", { postId, durationMs }, { optionalAuth: true });
    return data.recordView;
  }

  static async storiesFeed(): Promise<StoryRailItem[]> {
    const data = await request<{ storiesFeed: StoryRailItem[] }>("storiesFeed");
    return data.storiesFeed;
  }

  static async story(id: string): Promise<Story> {
    const data = await request<{ story: Story }>("story", { id });
    return data.story;
  }

  static async storyGroup(authorId: string, startStoryId?: string, archive = false, ownerType: "USER" | "ORGANIZATION" = "USER"): Promise<StoryGroup> {
    const data = await request<{ storyGroup: StoryGroup }>("storyGroup", { authorId, ownerType, startStoryId, archive });
    return data.storyGroup;
  }

  static async storyArchive(ownerId: string, cursor?: string | null, limit = 40, ownerType: "USER" | "ORGANIZATION" = "USER"): Promise<StoryArchiveResponse> {
    const data = await request<{ storyArchive: StoryArchiveResponse }>("storyArchive", { ownerId, ownerType, cursor, limit });
    return data.storyArchive;
  }

  static async storyArchivePeriods(ownerId: string, ownerType: "USER" | "ORGANIZATION" = "USER", limit = 60): Promise<StoryArchivePeriodsResponse> {
    const data = await request<{ storyArchivePeriods: StoryArchivePeriodsResponse }>("storyArchivePeriods", { ownerId, ownerType, limit });
    return data.storyArchivePeriods;
  }

  static async createStory(input: CreateStoryInput, files: File[] = []): Promise<Story> {
    if (files.length) throw new Error("Истории используют прямую загрузку в Media. Повторно выберите файл.");
    const normalized = {
      ...input,
      blocks: input.blocks.map((block) => ({
        ...block,
        data: {
          ...block.data,
          previewUrl: undefined,
        },
      })),
    };
    const data = await request<{ createStory: Story }>("createStory", { input: normalized });
    return data.createStory;
  }

  static async recordStoryView(storyId: string): Promise<boolean> {
    const data = await request<{ recordStoryView: boolean }>("recordStoryView", { storyId }, { optionalAuth: true });
    return data.recordStoryView;
  }

  static async likeStory(storyId: string): Promise<StoryReactionState> {
    const data = await request<{ likeStory: StoryReactionState }>("likeStory", { storyId });
    return data.likeStory;
  }

  static async unlikeStory(storyId: string): Promise<StoryReactionState> {
    const data = await request<{ unlikeStory: StoryReactionState }>("unlikeStory", { storyId });
    return data.unlikeStory;
  }

  static async createComment(input: { postId: string; text: string; blocks?: ContentBlock[]; attachments?: PostAsset[]; parentId?: string | null; replyToId?: string | null; document?: CommentDocumentV1 }) {
    const data = await request<{ createComment: CommentItem }>("createComment", { input: serializeCommentMediaInput(input) });
    return data.createComment;
  }

  /** Legacy helper retained for older callers. New comment media is uploaded
   * directly through `uploadMediaAsset` and sent as `attachments`. */
  static async createCommentWithFiles(input: { postId: string; text: string; blocks?: ContentBlock[]; attachments?: PostAsset[]; parentId?: string | null }, files: File[] = []): Promise<CommentItem> {
    const mediaInput = serializeCommentMediaInput(input);
    const data = files.length
      ? await multipartRequest<{ createComment: CommentItem }>("createComment", { input: mediaInput }, files)
      : await request<{ createComment: CommentItem }>("createComment", { input: mediaInput });
    return data.createComment;
  }

  static async updateCommentWithFiles(input: UpdateCommentInput, files: File[] = []): Promise<CommentItem> {
    const data = files.length
      ? await multipartRequest<{ updateComment: CommentItem }>("updateComment", { input }, files)
      : await request<{ updateComment: CommentItem }>("updateComment", { input });
    return data.updateComment;
  }

  static async deleteComment(id: string): Promise<boolean> {
    const data = await request<{ deleteComment: boolean }>("deleteComment", { id });
    return data.deleteComment;
  }

  static async deleteStory(id: string): Promise<boolean> {
    const data = await request<{ deleteStory: boolean }>("deleteStory", { id });
    return data.deleteStory;
  }

  static async comments(postId: string): Promise<CommentItem[]> {
    const data = await request<{ comments: CommentItem[] }>("comments", { postId });
    return data.comments;
  }

  static async commentThread(postOrInput: string | CommentThreadInput, sort: CommentSort = "TOP", limit = 100): Promise<CommentThreadResponse> {
    const input: CommentThreadInput = typeof postOrInput === "string"
      ? { postId: postOrInput, sort, limit }
      : { sort: "TOP", limit: 30, ...postOrInput };
    const data = await request<{ commentThread: CommentThreadResponse }>("commentThread", { input });
    return data.commentThread;
  }

  static async pinComment(commentId: string, pinned = true): Promise<CommentItem> {
    const data = await request<{ pinComment: CommentItem }>("pinComment", { commentId, pinned });
    return data.pinComment;
  }

  static async hideComment(commentId: string): Promise<CommentItem> {
    const data = await request<{ hideComment: CommentItem }>("hideComment", { commentId });
    return data.hideComment;
  }

  static async reportComment(commentId: string, reason: string): Promise<boolean> {
    const data = await request<{ reportComment: boolean }>("reportComment", { input: { commentId, reason } });
    return data.reportComment;
  }

  static async votePoll(postId: string, blockId: string, optionId: string): Promise<PollVoteState> {
    const data = await request<{ votePoll: PollVoteState }>("votePoll", { input: { postId, blockId, optionId } });
    return data.votePoll;
  }

  static async closePoll(postId: string, blockId: string): Promise<PollVoteState> {
    const data = await request<{ closePoll: PollVoteState }>("closePoll", { postId, blockId });
    return data.closePoll;
  }

  static async likeComment(commentId: string): Promise<CommentReactionState> {
    const data = await request<{ likeComment: CommentReactionState }>("likeComment", { commentId });
    return data.likeComment;
  }

  static async unlikeComment(commentId: string): Promise<CommentReactionState> {
    const data = await request<{ unlikeComment: CommentReactionState }>("unlikeComment", { commentId });
    return data.unlikeComment;
  }

  static textFromBlocks(blocks: ContentBlock[]): string {
    return blockText(blocks);
  }

  static mediaSource(block: ContentBlock | StoryBlock): string {
    return valueAsString(block.data.previewUrl)
      || valueAsString(block.data.url)
      || valueAsString(block.data.src)
      || (valueAsString(block.data.blobId) ? `/content-media/${encodeURIComponent(valueAsString(block.data.blobId))}` : "");
  }
}
