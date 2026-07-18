import { mount } from "@vue/test-utils";
import { createMemoryHistory, createRouter } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { router as appRouter } from "@/app/router";
import { contentUrl } from "@/shared/api/navigation";
import { ProfileService } from "@/shared/api/profileService";
import { ContentService } from "@/shared/api/contentService";
import AppShell from "@/features/shell/ui/AppShell.vue";
import App from "@/app/App.vue";
import { filterProfileNavigation } from "@/features/embed/lib/profileEmbed";
import type { ProfileNavButton } from "@/shared/api/types";

vi.mock("@/shared/api/profileService", () => ({
  ProfileService: {
    session: vi.fn().mockResolvedValue({ id: "viewer", username: "viewer" }),
  },
}));

vi.mock("@/shared/api/contentService", () => ({
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
  it("keeps /me as a profile-owned route", async () => {
    await appRouter.push("/me");
    await appRouter.isReady();

    expect(ProfileService.session).toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).toBe("/me");
  });

  it("redirects content-owned paths before checking the profile session", async () => {
    const result = await appRouter.push("/p/post-1?comment=latest");

    expect(result).toBeTruthy();
    expect(ProfileService.session).not.toHaveBeenCalled();
    expect(appRouter.currentRoute.value.path).not.toBe("/p/post-1");
  });

  it("redirects content creation and story paths before checking the profile session", async () => {
    await appRouter.push("/p/new");
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

describe("profile embed mode", () => {
  it("renders routed content without the Profile shell when embed is enabled", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/u/:nickname", component: { template: "<section class=\"embedded-profile\">profile</section>" } }],
    });
    await router.push("/u/alice?embed=1&from=content&parentOrigin=http%3A%2F%2Fcontent.test");
    await router.isReady();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
        components: { PToast: { template: "<div />" } },
      },
    });

    expect(wrapper.find(".embedded-profile").exists()).toBe(true);
    expect(wrapper.find(".app-shell").exists()).toBe(false);
    expect(wrapper.find(".avatar-menu-button").exists()).toBe(false);

    wrapper.unmount();
  });

  it("keeps the Profile shell in standalone mode", async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/u/:nickname", component: { template: "<section>profile</section>" } }],
    });
    await router.push("/u/alice");
    await router.isReady();

    const wrapper = mount(App, {
      global: {
        plugins: [router],
        components: { PToast: { template: "<div />" } },
      },
    });

    expect(wrapper.find(".app-shell").exists()).toBe(true);

    wrapper.unmount();
  });

  it("filters external service buttons while keeping profile-core navigation", () => {
    const buttons: ProfileNavButton[] = [
      { key: "collections", serviceKey: "profile", featureKey: "collections", label: "Collections", icon: "pi pi-bookmark", color: "#111", kind: "collections" },
      { key: "posts", serviceKey: "content", featureKey: "posts", label: "Posts", icon: "pi pi-th-large", color: "#111", kind: "section" },
      { key: "clips", serviceKey: "media", featureKey: "clips", label: "Clips", icon: "pi pi-video", color: "#111", kind: "section" },
    ];

    expect(filterProfileNavigation(buttons, { query: {} } as never).map((button) => button.key)).toEqual(["collections", "posts", "clips"]);
    expect(filterProfileNavigation(buttons, { query: { from: "content" } } as never).map((button) => button.key)).toEqual(["collections", "posts"]);
    expect(filterProfileNavigation(buttons, { query: { from: "unknown" } } as never).map((button) => button.key)).toEqual(["collections"]);
  });
});
