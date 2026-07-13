import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/runtime-config";
import { redirectToAccount } from "@/api/authRedirect";
import { ContentService } from "@/api/contentService";
import { isProfilePath, profileUrl } from "@/api/navigation";
import FeedPage from "@/features/content/FeedPage.vue";
import PostOverlay from "@/features/post/PostOverlay.vue";

let sessionCheck: Promise<unknown> | null = null;
const ExternalRedirect = { template: "<span />" };

function requireSession(): Promise<unknown> {
  sessionCheck = ContentService.currentActor().catch((error) => {
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
      path: "/p/:postId",
      name: "PostOverlay",
      component: PostOverlay,
    },
    {
      path: "/post/new",
      name: "CreatePost",
      component: () => import("@/features/editor/PostCreatePage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/post/:postId/edit",
      name: "EditPost",
      component: () => import("@/features/editor/PostCreatePage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/story/new",
      name: "CreateStory",
      component: () => import("@/features/stories/StoryCreatePage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/stories/archive",
      name: "StoryArchive",
      component: () => import("@/features/stories/StoryArchivePage.vue"),
    },
    {
      path: "/story/:storyId",
      name: "StoryViewer",
      component: () => import("@/features/stories/StoryViewer.vue"),
    },
    {
      path: "/u/:nickname/social",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/o/:orgname/social",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/u/:nickname/collections/:collectionId",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/o/:orgname/collections/:collectionId",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/u/:nickname",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/o/:orgname",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/search",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(profileUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/",
    },
  ],
});

router.beforeEach(async (to) => {
  if (isProfilePath(to.path)) {
    window.location.assign(profileUrl(to.fullPath, true));
    return false;
  }

  if (to.meta.requiresAuth) {
    try {
      await requireSession();
      return true;
    } catch {
      redirectToAccount();
      return false;
    }
  }
  return true;
});
