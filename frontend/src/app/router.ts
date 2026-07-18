import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/shared/config/runtime";
import { redirectToAccount } from "@/shared/api/authRedirect";
import { ProfileService } from "@/shared/api/profileService";
import { contentUrl, isContentPath } from "@/shared/api/navigation";
import { isEmbeddedProfile, postEmbedNavigation } from "@/features/embed/lib/profileEmbed";

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
      component: () => import("@/pages/redirect/ProfileHomeRedirect.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/me",
      name: "ProfileMe",
      component: () => import("@/pages/redirect/ProfileHomeRedirect.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/search",
      name: "Search",
      component: () => import("@/pages/search/SearchPage.vue"),
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
      component: () => import("@/pages/social/SocialCanvasPage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/o/:orgname/social",
      name: "OrganizationSocialCanvas",
      component: () => import("@/pages/social/SocialCanvasPage.vue"),
      meta: { requiresAuth: true },
    },
    {
      path: "/u/:nickname/collections/:collectionId",
      name: "CollectionCanvas",
      component: () => import("@/pages/collection/CollectionCanvasPage.vue"),
    },
    {
      path: "/o/:orgname/collections/:collectionId",
      name: "OrganizationCollectionCanvas",
      component: () => import("@/pages/collection/CollectionCanvasPage.vue"),
    },
    {
      path: "/u/:nickname",
      name: "Profile",
      component: () => import("@/pages/profile/ProfilePage.vue"),
    },
    {
      path: "/o/:orgname",
      name: "OrganizationProfile",
      component: () => import("@/pages/profile/ProfilePage.vue"),
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/",
    },
  ],
});

router.beforeEach(async (to) => {
  if (isContentPath(to.path)) {
    if (isEmbeddedProfile(to) && postEmbedNavigation(to, { serviceKey: "content", path: to.fullPath, url: contentUrl(to.fullPath, true) })) {
      return false;
    }
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
