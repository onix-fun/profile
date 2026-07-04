import { createRouter, createWebHistory } from "vue-router";
import { runtimeConfig } from "@/runtime-config";

export const router = createRouter({
  history: createWebHistory(runtimeConfig.frontendBasePath),
  routes: [
    {
      path: "/",
      redirect: "/u/me",
    },
    {
      path: "/u/:nickname",
      name: "Profile",
      component: () => import("@/features/profile/ProfilePage.vue"),
    },
    {
      path: "/:pathMatch(.*)*",
      redirect: "/u/me",
    },
  ],
});
