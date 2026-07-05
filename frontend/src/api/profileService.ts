import { api } from "@/api/client";
import type { AccountSearchUser, ProfileCanvasResponse, Relationship, SessionUser } from "@/api/types";

export class ProfileService {
  static async session(): Promise<SessionUser> {
    const response = await api.get<{ user: SessionUser }>("/session/me");
    return response.data.user;
  }

  static async getProfile(nickname: string): Promise<ProfileCanvasResponse> {
    const response = await api.get<ProfileCanvasResponse>(`/profiles/${encodeURIComponent(nickname)}`);
    return response.data;
  }

  static async follow(userId: string): Promise<Relationship> {
    const response = await api.post<{ relationship: Relationship }>(`/profiles/${userId}/follow`);
    return response.data.relationship;
  }

  static async unfollow(userId: string): Promise<void> {
    await api.delete(`/profiles/${userId}/follow`);
  }

  static async searchUsers(query: string, limit = 10): Promise<AccountSearchUser[]> {
    const response = await api.get<AccountSearchUser[]>("/profile-search/users", {
      params: { q: query, limit },
    });
    return response.data;
  }
}
