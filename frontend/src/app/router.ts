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
      path: "/o/:orgname/stories/archive",
      name: "OrganizationStoryArchive",
      component: () => import("@/features/stories/StoryArchivePage.vue"),
    },
    {
      path: "/u/:nickname/social",
      name: "SocialCanvas",
      component: () => import("@/features/profile/SocialCanvasPage.vue"),
    },
    {
      path: "/o/:orgname/social",
      name: "OrganizationSocialCanvas",
      component: () => import("@/features/profile/SocialCanvasPage.vue"),
    },
    {
      path: "/u/:nickname/collections/:collectionId",
      name: "CollectionCanvas",
      component: () => import("@/features/profile/CollectionCanvasPage.vue"),
    },
    {
      path: "/o/:orgname/collections/:collectionId",
      name: "OrganizationCollectionCanvas",
      component: () => import("@/features/profile/CollectionCanvasPage.vue"),
    },
    {
      path: "/u/:nickname",
      name: "Profile",
      component: () => import("@/features/profile/ProfilePage.vue"),
    },
    {
      path: "/o/:orgname",
      name: "OrganizationProfile",
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
