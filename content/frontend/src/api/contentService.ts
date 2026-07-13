import axios from "axios";
import { refreshBrowserSession } from "@/api/client";
import { redirectToAccount } from "@/api/authRedirect";
import { runtimeConfig } from "@/runtime-config";
import type {
  CommentItem,
  CommentReactionState,
  ContentBlock,
  CollectionDetail,
  CreateCollectionInput,
  CreatePostInput,
  CreateStoryInput,
  CurrentActor,
  FeedItem,
  PostCollectionsState,
  RecommendationFeedInput,
  RecommendationFeedResponse,
  SavedCollection,
  PostReactionState,
  Story,
  StoryArchiveResponse,
  StoryBlock,
  StoryGroup,
  StoryRailItem,
  StoryReactionState,
  UpdateCommentInput,
  UpdatePostInput,
} from "@/api/types";

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
    const data = files.length
      ? await multipartRequest<{ createPost: FeedItem["post"] }>("createPost", { input }, files)
      : await request<{ createPost: FeedItem["post"] }>("createPost", { input });
    return data.createPost;
  }

  static async updatePost(input: UpdatePostInput, files: File[] = []): Promise<FeedItem["post"]> {
    const data = files.length
      ? await multipartRequest<{ updatePost: FeedItem["post"] }>("updatePost", { input }, files)
      : await request<{ updatePost: FeedItem["post"] }>("updatePost", { input });
    return data.updatePost;
  }

  static async deletePost(id: string): Promise<boolean> {
    const data = await request<{ deletePost: boolean }>("deletePost", { id });
    return data.deletePost;
  }

  static async post(id: string): Promise<FeedItem["post"] | null> {
    const data = await request<{ post: FeedItem["post"] | null }>("post", { id });
    return data.post;
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

  static async createStory(input: CreateStoryInput, files: File[] = []): Promise<Story> {
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
    const data = files.length
      ? await multipartRequest<{ createStory: Story }>("createStory", { input: normalized }, files)
      : await request<{ createStory: Story }>("createStory", { input: normalized });
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

  static async createComment(input: { postId: string; text: string; blocks?: ContentBlock[] }) {
    const data = await request<{ createComment: CommentItem }>("createComment", { input });
    return data.createComment;
  }

  static async createCommentWithFiles(input: { postId: string; text: string; blocks: ContentBlock[] }, files: File[] = []): Promise<CommentItem> {
    const data = files.length
      ? await multipartRequest<{ createComment: CommentItem }>("createComment", { input }, files)
      : await request<{ createComment: CommentItem }>("createComment", { input });
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
