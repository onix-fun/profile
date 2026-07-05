import axios from "axios";
import { runtimeConfig } from "@/runtime-config";
import { refreshBrowserSession } from "@/api/client";
import type {
  CommentItem,
  ContentBlock,
  CreatePostInput,
  CreateStoryInput,
  FeedItem,
  Story,
  StoryBlock,
  StoryRailItem,
} from "@/api/types";

const graphql = axios.create({
  baseURL: "",
  timeout: 12000,
  withCredentials: true,
});

graphql.interceptors.request.use((config) => {
  config.headers.set("X-Profile-Redirect", window.location.href);
  return config;
});

graphql.interceptors.response.use(
  (response) => response,
  async (error) => {
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
      const redirect = encodeURIComponent(window.location.href);
      window.location.assign(`${runtimeConfig.accountFrontendUrl}/?redirect=${redirect}`);
      return new Promise(() => undefined);
    }
    return Promise.reject(error);
  },
);

interface GraphQlResponse<T> {
  data?: T;
  errors?: Array<{ message: string }>;
}

async function request<T>(operationName: string, variables: Record<string, unknown> = {}): Promise<T> {
  const response = await graphql.post<GraphQlResponse<T>>("/graphql", {
    operationName,
    variables,
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
  const response = await graphql.post<GraphQlResponse<T>>("/graphql", form);
  const error = response.data.errors?.[0]?.message;
  if (error) throw new Error(error);
  if (!response.data.data) throw new Error("Empty content response");
  return response.data.data;
}

export class ContentService {
  static async feed(tags: string[] = [], limit = 30): Promise<FeedItem[]> {
    const data = await request<{ feed: FeedItem[] }>("feed", { tags, limit });
    return data.feed;
  }

  static async createPost(input: CreatePostInput, files: File[] = []): Promise<FeedItem["post"]> {
    const data = files.length
      ? await multipartRequest<{ createPost: FeedItem["post"] }>("createPost", { input }, files)
      : await request<{ createPost: FeedItem["post"] }>("createPost", { input });
    return data.createPost;
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

  static async likePost(postId: string): Promise<boolean> {
    const data = await request<{ likePost: boolean }>("likePost", { postId });
    return data.likePost;
  }

  static async unlikePost(postId: string): Promise<boolean> {
    const data = await request<{ unlikePost: boolean }>("unlikePost", { postId });
    return data.unlikePost;
  }

  static async recordView(postId: string, durationMs = 0): Promise<boolean> {
    const data = await request<{ recordView: boolean }>("recordView", { postId, durationMs });
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
    const data = await request<{ recordStoryView: boolean }>("recordStoryView", { storyId });
    return data.recordStoryView;
  }

  static async createComment(input: { postId: string; parentId?: string; text: string }) {
    const data = await request<{ createComment: { id: string; text: string } }>("createComment", { input });
    return data.createComment;
  }

  static async comments(postId: string): Promise<CommentItem[]> {
    const data = await request<{ comments: CommentItem[] }>("comments", { postId });
    return data.comments;
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
