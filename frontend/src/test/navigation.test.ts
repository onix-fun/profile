import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { router as appRouter } from "@/app/router";
import { contentUrl } from "@/api/navigation";
import { ProfileService } from "@/api/profileService";
import { ContentService } from "@/api/contentService";
import AppShell from "@/features/shell/AppShell.vue";

vi.mock("@/api/profileService", () => ({
  ProfileService: {
    session: vi.fn().mockResolvedValue({ id: "viewer", username: "viewer" }),
  },
}));

vi.mock("@/api/contentService", () => ({
  ContentService: {
    currentActor: vi.fn().mockResolvedValue({
      user: { id: "viewer", username: "viewer" },
      activeOwner: { id: "viewer", ownerType: "USER", username: "viewer" },
    }),
  },
}));

beforeEach(() => {
  vi.clearAllMocks();
});

describe("route auth guard", () => {
  it("redirects content-owned paths before checking the profile session", async () => {
    const result = await appRouter.push("/p/post-1?comment=latest");

    expect(result).toBeTruthy();
    expect(ProfileService.session).not.toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).not.toBe("/p/post-1");
  });

  it("redirects content creation and story paths before checking the profile session", async () => {
    await appRouter.push("/post/new");
    await appRouter.push("/story/story-1");

    expect(ProfileService.session).not.toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).not.toBe("/story/story-1");
  });
});

describe("navigation helpers", () => {
  it("builds clean content links unless redirect back is requested", () => {
    const clean = new URL(contentUrl("/p/post-1?comment=latest"));
    expect(clean.origin).toBe("http://content.onix.localhost:8088");
    expect(clean.pathname).toBe("/p/post-1");
    expect(clean.searchParams.get("comment")).toBe("latest");
    expect(clean.searchParams.has("redirect")).toBe(false);

    const withRedirect = new URL(contentUrl("/story/story-1", true));
    expect(withRedirect.origin).toBe("http://content.onix.localhost:8088");
    expect(withRedirect.pathname).toBe("/story/story-1");
    expect(withRedirect.searchParams.get("redirect")).toBe(window.location.href);
  });

  it("builds Content-owned story archive links with redirect back to Profile", () => {
    const archive = new URL(contentUrl("/stories/archive?ownerType=USER&ownerId=owner-1", true));

    expect(archive.origin).toBe("http://content.onix.localhost:8088");
    expect(archive.pathname).toBe("/stories/archive");
    expect(archive.searchParams.get("ownerType")).toBe("USER");
    expect(archive.searchParams.get("ownerId")).toBe("owner-1");
    expect(archive.searchParams.get("redirect")).toBe(window.location.href);
  });
});

describe("app shell navigation", () => {
  it("uses absolute content links for feed navigation", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: { template: "<div />" } },
        { path: "/search", component: { template: "<div />" } },
      ],
    });
    router.push("/search");
    await router.isReady();

    const wrapper = mount(AppShell, {
      global: { plugins: [router] },
      slots: { default: "<div>search</div>" },
    });
    await Promise.resolve();
    await wrapper.find(".avatar-menu-button").trigger("click");

    expect(wrapper.find(".brand-mark").attributes("href")).toBe("http://content.onix.localhost:8088/");
    expect(wrapper.find('.account-menu__nav a[href="http://content.onix.localhost:8088/"]').exists()).toBe(true);
    expect(ContentService.currentActor).toHaveBeenCalled();
  });
});
