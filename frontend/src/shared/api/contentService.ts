import { api } from "@/shared/api/client";
import type {
  CollectionDetail,
  CollectionItemRef,
  ContentBlock,
  CreateCollectionInput,
  CurrentActor,
  ItemCollectionsState,
  PostCollectionsState,
  PostReactionState,
  RecommendationFeedInput,
  RecommendationFeedResponse,
  SavedCollection,
  StoryBlock,
} from "@/shared/api/types";
import { contentUrl } from "@/shared/api/navigation";

function blockText(blocks: ContentBlock[]): string {
  return blocks.map((block) => {
    const value = block.data.text;
    return typeof value === "string" ? value : "";
  }).filter(Boolean).join("\n");
}

function valueAsString(value: unknown): string {
  return typeof value === "string" ? value : "";
}

export class ContentService {
  static async currentActor(): Promise<CurrentActor> {
    const response = await api.get<{ actor: CurrentActor }>("/session/actor", {
      headers: { "X-Onix-Optional-Auth": "1" },
    });
    return response.data.actor;
  }

  static async recommendationFeed(input: RecommendationFeedInput): Promise<RecommendationFeedResponse> {
    const response = await api.get<RecommendationFeedResponse>("/content/recommendations", { params: input });
    return response.data;
  }

  static async collections(ownerId: string, ownerType: "USER" | "ORGANIZATION" = "USER", limit = 80): Promise<SavedCollection[]> {
    const response = await api.get<SavedCollection[]>(`/owners/${ownerType}/${encodeURIComponent(ownerId)}/collections`, { params: { limit } });
    return response.data;
  }

  static async collection(id: string, limit = 200): Promise<CollectionDetail> {
    const response = await api.get<CollectionDetail>(`/collections/${encodeURIComponent(id)}`, { params: { limit } });
    return response.data;
  }

  static async createCollection(input: CreateCollectionInput): Promise<SavedCollection> {
    const response = await api.post<SavedCollection>("/collections", input);
    return response.data;
  }

  static async itemCollections(ref: CollectionItemRef): Promise<ItemCollectionsState> {
    const response = await api.get<ItemCollectionsState>(itemCollectionsPath(ref));
    return response.data;
  }

  static async setItemCollections(ref: CollectionItemRef, collectionIds: string[]): Promise<ItemCollectionsState> {
    const response = await api.put<ItemCollectionsState>(itemCollectionsPath(ref), { collectionIds });
    return response.data;
  }

  static async postCollections(postId: string): Promise<PostCollectionsState> {
    const state = await ContentService.itemCollections({ serviceKey: "content", itemType: "post", itemId: postId });
    return { postId, collectionIds: state.collectionIds };
  }

  static async setPostCollections(postId: string, collectionIds: string[]): Promise<PostCollectionsState> {
    const state = await ContentService.setItemCollections({ serviceKey: "content", itemType: "post", itemId: postId }, collectionIds);
    return { postId, collectionIds: state.collectionIds };
  }

  static async likePost(postId: string): Promise<PostReactionState> {
    const response = await api.post<PostReactionState>(`/content/posts/${encodeURIComponent(postId)}/like`);
    return response.data;
  }

  static async unlikePost(postId: string): Promise<PostReactionState> {
    const response = await api.delete<PostReactionState>(`/content/posts/${encodeURIComponent(postId)}/like`);
    return response.data;
  }

  static textFromBlocks(blocks: ContentBlock[]): string {
    return blockText(blocks);
  }

  static mediaSource(block: ContentBlock | StoryBlock): string {
    const source = valueAsString(block.data.previewUrl)
      || valueAsString(block.data.url)
      || valueAsString(block.data.src)
      || (valueAsString(block.data.blobId)
        ? contentUrl(`/content-media/${encodeURIComponent(valueAsString(block.data.blobId))}`)
        : "");
    return source.startsWith("/content-media/") ? contentUrl(source) : source;
  }
}

function itemCollectionsPath(ref: CollectionItemRef): string {
  return `/items/${encodeURIComponent(ref.serviceKey)}/${encodeURIComponent(ref.itemType)}/${encodeURIComponent(ref.itemId)}/collections`;
}
