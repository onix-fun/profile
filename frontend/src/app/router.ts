import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/runtime-config";
import { redirectToAccount } from "@/api/authRedirect";
import { ProfileService } from "@/api/profileService";
import { contentUrl, isContentPath } from "@/api/navigation";

let sessionCheck: Promise<unknown> | null = null;
const ExternalRedirect = { template: "<span />" };

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
      name: "ProfileHome",
      component: () => import("@/features/profile/ProfileHomeRedirect.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/search",
      name: "Search",
      component: () => import("@/features/content/SearchPage.vue"),
    },
    {
      path: "/p/:postId",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(contentUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/post/new",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(contentUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/story/new",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(contentUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/story/:storyId",
      component: ExternalRedirect,
      beforeEnter: (to) => {
        window.location.assign(contentUrl(to.fullPath, true));
        return false;
      },
    },
    {
      path: "/u/:nickname/social",
      name: "SocialCanvas",
      component: () => import("@/features/profile/SocialCanvasPage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/o/:orgname/social",
      name: "OrganizationSocialCanvas",
      component: () => import("@/features/profile/SocialCanvasPage.vue"),
      meta: { requiresAuth: true },
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

router.beforeEach(async (to) => {
  if (isContentPath(to.path)) {
    window.location.assign(contentUrl(to.fullPath, true));
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
