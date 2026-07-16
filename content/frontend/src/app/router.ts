import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/runtime-config";
import { redirectToAccount } from "@/api/authRedirect";
import { ContentService } from "@/api/contentService";
import FeedPage from "@/features/content/FeedPage.vue";

let sessionCheck: Promise<unknown> | null = null;

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
      path: "/p/new",
      name: "CreatePost",
      component: () => import("@/features/editor/PostCreatePage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/p/:postId/comments/:commentId",
      name: "PostCommentThread",
      component: () => import("@/features/comments/CommentsPage.vue"),
    },
    {
      path: "/p/:postId/comments",
      name: "PostComments",
      component: () => import("@/features/comments/CommentsPage.vue"),
    },
    {
      path: "/p/:postId",
      name: "PostPage",
      component: () => import("@/features/post/PostPage.vue"),
    },
    {
      path: "/p/:postId/edit",
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
      path: "/me",
      name: "ProfileMeFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/u/:nickname/social",
      name: "ProfileSocialFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/o/:orgname/social",
      name: "OrganizationSocialFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/u/:nickname/collections/:collectionId",
      name: "ProfileCollectionFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/o/:orgname/collections/:collectionId",
      name: "OrganizationCollectionFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/u/:nickname",
      name: "ProfileFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/o/:orgname",
      name: "OrganizationProfileFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/search",
      name: "ProfileSearchFrame",
      component: () => import("@/features/profile/ProfileFramePage.vue"),
    },
    {
      path: "/post/:pathMatch(.*)*",
      name: "RemovedPostRoute",
      component: () => import("@/features/shell/NotFoundPage.vue"),
    },
    {
      path: "/:pathMatch(.*)*",
      name: "NotFound",
      component: () => import("@/features/shell/NotFoundPage.vue"),
    },
  ],
});

router.beforeEach(async (to) => {
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
