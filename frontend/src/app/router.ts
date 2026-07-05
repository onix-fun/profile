import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/runtime-config";
import { redirectToAccount } from "@/api/authRedirect";
import { ProfileService } from "@/api/profileService";
import FeedPage from "@/features/content/FeedPage.vue";
import PostOverlay from "@/features/post/PostOverlay.vue";

let sessionCheck: Promise<unknown> | null = null;

function requireSession(): Promise<unknown> {
  sessionCheck = ProfileService.session().catch((error) => {
    sessionCheck = null;
    throw error;
  });
  return sessionCheck;
}

export const router = createRouter({
  history: createWebHistory(runtimeConfig.frontendBasePath),
  routes: [
    {
      path: "/",
      name: "Feed",
      component: FeedPage,
    },
    {
      path: "/search",
      name: "Search",
      component: () => import("@/features/content/SearchPage.vue"),
    },
    {
      path: "/p/:postId",
      name: "PostOverlay",
      component: PostOverlay,
    },
    {
      path: "/post/new",
      name: "CreatePost",
      component: () => import("@/features/editor/PostCreatePage.vue"),
    },
    {
      path: "/story/new",
      name: "CreateStory",
      component: () => import("@/features/stories/StoryCreatePage.vue"),
    },
    {
      path: "/story/:storyId",
      name: "StoryViewer",
      component: () => import("@/features/stories/StoryViewer.vue"),
    },
    {
      path: "/u/:nickname/stories/archive",
      name: "StoryArchive",
      component: () => import("@/features/stories/StoryArchivePage.vue"),
    },
    {
      path: "/u/:nickname",
      name: "Profile",
      component: () => import("@/features/profile/ProfilePage.vue"),
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/",
    },
  ],
});

router.beforeEach(async () => {
  try {
    await requireSession();
    return true;
  } catch {
    redirectToAccount();
    return false;
  }
});
