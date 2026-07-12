import { api } from "@/api/client";
import type { AccountSearchUser, ProfileCanvasResponse, Relationship, SessionUser, SocialCanvasResponse, SocialFilter } from "@/api/types";

export class ProfileService {
  static async session(): Promise<SessionUser> {
    const response = await api.get<{ user: SessionUser }>("/session/me");
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
