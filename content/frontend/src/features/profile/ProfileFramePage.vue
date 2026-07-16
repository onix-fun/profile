<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { runtimeConfig } from "@/runtime-config";
import { ContentService } from "@/api/contentService";

interface OnixNavigateMessage {
  type: "onix:navigate";
  serviceKey?: string;
  path?: string;
  url?: string;
}

interface OnixProfileRouteMessage {
  type: "onix:profile-route";
  path?: string;
}

type FrameMessage = OnixNavigateMessage | OnixProfileRouteMessage;

const route = useRoute();
const router = useRouter();
const profileOrigin = new URL(runtimeConfig.profileFrontendUrl).origin;
const isResolvingMe = ref(false);

const frameSrc = computed(() => {
  const url = new URL(route.fullPath, `${runtimeConfig.profileFrontendUrl}/`);
  url.searchParams.set("embed", "1");
  if (!url.searchParams.has("from")) url.searchParams.set("from", "content");
  url.searchParams.set("parentOrigin", window.location.origin);
  return url.toString();
});

const frameTitle = computed(() => {
  if (route.path === "/search") return "Profile search";
  if (route.path.startsWith("/o/")) return "Organization profile";
  return "Profile";
});

onMounted(() => {
  window.addEventListener("message", onFrameMessage);
  void resolveMeRoute();
});

onBeforeUnmount(() => {
  window.removeEventListener("message", onFrameMessage);
});

watch(() => route.fullPath, () => {
  void resolveMeRoute();
});

async function resolveMeRoute() {
  if ((route.path !== "/me" && route.path !== "/u/me") || isResolvingMe.value) return;
  isResolvingMe.value = true;
  try {
    const actor = await ContentService.currentActor();
    const owner = actor.activeOwner;
    const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
    await router.replace({
      path: `/${prefix}/${encodeURIComponent(owner.username)}`,
      query: route.query,
    });
  } catch {
    // Keep /u/me available for Profile's own auth flow if Content has no actor.
  } finally {
    isResolvingMe.value = false;
  }
}

function onFrameMessage(event: MessageEvent<FrameMessage>) {
  if (event.origin !== profileOrigin || !event.data || typeof event.data !== "object") return;
  if (event.data.type === "onix:navigate") {
    handleNavigate(event.data);
    return;
  }
  if (event.data.type === "onix:profile-route" && typeof event.data.path === "string") {
    const nextPath = normalizeProfilePath(event.data.path);
    if (nextPath !== route.fullPath) void router.replace(nextPath);
  }
}

function handleNavigate(message: OnixNavigateMessage) {
  if (message.serviceKey === "content" && message.path?.startsWith("/")) {
    void router.push(message.path);
    return;
  }
  if (message.url) window.location.assign(message.url);
}

function normalizeProfilePath(path: string): string {
  const url = new URL(path, window.location.origin);
  url.searchParams.delete("embed");
  url.searchParams.delete("parentOrigin");
  if (!Object.prototype.hasOwnProperty.call(route.query, "from")) url.searchParams.delete("from");
  return `${url.pathname}${url.search}${url.hash}`;
}
</script>

<template>
  <section class="profile-frame-page" aria-label="Profile service">
    <iframe
      v-if="!isResolvingMe"
      class="profile-frame-page__frame"
      :src="frameSrc"
      :title="frameTitle"
      allow="clipboard-write; fullscreen"
    ></iframe>
  </section>
</template>

<style scoped>
.profile-frame-page {
  min-height: 100dvh;
  background: var(--comic-paper);
}

.profile-frame-page__frame {
  width: 100%;
  min-height: 100dvh;
  border: 0;
  display: block;
  background: transparent;
}
</style>
