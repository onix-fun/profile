import { api } from "@/api/client";
import type {
  AccountSearchUser,
  ProfileCanvasResponse,
  Relationship,
  SearchResponse,
  SearchSuggestResponse,
  SessionUser,
  SocialCanvasResponse,
  SocialFilter,
} from "@/api/types";

export class ProfileService {
  static async session(): Promise<SessionUser> {
    const response = await api.get<{ user: SessionUser }>("/session/me", {
      headers: { "X-Onix-Optional-Auth": "1" },
    });
    return response.data.user;
  }

  static async getProfile(nickname: string): Promise<ProfileCanvasResponse> {
    const response = await api.get<ProfileCanvasResponse>(`/profiles/${encodeURIComponent(nickname)}`);
    return response.data;
  }

  static async getOrganization(orgName: string): Promise<ProfileCanvasResponse> {
    const response = await api.get<ProfileCanvasResponse>(`/organizations/${encodeURIComponent(orgName)}`);
    return response.data;
  }

  static async follow(ownerId: string, ownerType: "USER" | "ORGANIZATION" = "USER"): Promise<Relationship> {
    const response = await api.post<{ relationship: Relationship }>(`/owners/${ownerType}/${ownerId}/follow`);
    return response.data.relationship;
  }

  static async unfollow(ownerId: string, ownerType: "USER" | "ORGANIZATION" = "USER"): Promise<void> {
    await api.delete(`/owners/${ownerType}/${ownerId}/follow`);
  }

  static async searchUsers(query: string, limit = 10): Promise<AccountSearchUser[]> {
    const response = await api.get<AccountSearchUser[]>("/profile-search/users", {
      params: { q: query, limit },
    });
    return response.data;
  }

  static async searchOwners(query: string, limit = 10): Promise<AccountSearchUser[]> {
    const response = await api.get<AccountSearchUser[]>("/profile-search/owners", {
      params: { q: query, limit },
    });
    return response.data;
  }

  static async search(params: {
    q: string;
    types?: string[];
    tags?: string[];
    providers?: string[];
    author?: string;
    dateFrom?: string;
    dateTo?: string;
    sort?: "relevance" | "new" | "popular";
    limit?: number;
    cursor?: string | null;
  }): Promise<SearchResponse> {
    const response = await api.get<SearchResponse>("/search", {
      params: {
        q: params.q,
        types: params.types?.join(","),
        tags: params.tags?.join(","),
        providers: params.providers?.join(","),
        author: params.author,
        dateFrom: params.dateFrom,
        dateTo: params.dateTo,
        sort: params.sort,
        limit: params.limit,
        cursor: params.cursor || undefined,
      },
    });
    return response.data;
  }

  static async searchSuggest(query: string, limit = 8): Promise<SearchSuggestResponse> {
    const response = await api.get<SearchSuggestResponse>("/search/suggest", {
      params: { q: query, limit },
    });
    return response.data;
  }

  static async getSocial(nickname: string, filter: SocialFilter = "friends", page = 1, limit = 60): Promise<SocialCanvasResponse> {
    const response = await api.get<SocialCanvasResponse>(`/profiles/${encodeURIComponent(nickname)}/social`, {
      params: { filter, page, limit },
    });
    return response.data;
  }

  static async getOrganizationSocial(orgName: string, filter: SocialFilter = "friends", page = 1, limit = 60): Promise<SocialCanvasResponse> {
    const response = await api.get<SocialCanvasResponse>(`/organizations/${encodeURIComponent(orgName)}/social`, {
      params: { filter, page, limit },
    });
    return response.data;
  }
}
