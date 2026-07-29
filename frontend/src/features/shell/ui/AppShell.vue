<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import type { OnixGraphMenuMode } from "@onix/design-system";
import { ContentService } from "@/shared/api/contentService";
import type { CurrentActor } from "@/shared/api/types";
import { runtimeConfig } from "@/shared/config/runtime";
import { accountSettingsUrl } from "@/features/profile/lib/accountLinks";
import { contentUrl } from "@/shared/api/navigation";
import { i18n } from "@/shared/i18n";
import GraphMenu from "@/shared/ui/GraphMenu.vue";
import type { GraphMenuItem } from "@/shared/ui/GraphMenu.vue";
import OnixIcon from "@/shared/ui/OnixIcon.vue";

const route = useRoute();
const router = useRouter();
const t = i18n.global.t;
const actor = ref<CurrentActor | null>(null);
const mode = ref<OnixGraphMenuMode>("main");

const activeOwner = computed(() => actor.value?.activeOwner || null);
const ownerName = computed(() => {
  const owner = activeOwner.value;
  return owner?.displayName || [owner?.firstName, owner?.lastName].filter(Boolean).join(" ") || owner?.username || t("nav.signIn");
});
const accountHref = computed(() => accountSettingsUrl(runtimeConfig.accountFrontendUrl, window.location.href));
const isProfileRoute = computed(() => route.name !== "Search");

const mainItems = computed<GraphMenuItem[]>(() => [
  { id: "account", label: ownerName.value, meta: activeOwner.value ? `@${activeOwner.value.username}` : undefined, icon: "user", avatarUrl: activeOwner.value?.avatarUrl, tone: "neutral" },
  { id: "profile", label: t("nav.profile"), icon: "user", tone: "neutral", active: isProfileRoute.value },
  { id: "feed", label: t("nav.feed"), icon: "home", tone: "neutral" },
  { id: "create", label: t("nav.create"), icon: "add", tone: "pink" },
]);
const creationItems = computed<GraphMenuItem[]>(() => [
  ...mainItems.value.map((item) => ({ ...item, disabled: item.id !== "create" })),
  { id: "new-post", label: t("nav.createPost"), icon: "edit", tone: "info" },
  { id: "new-story", label: t("nav.createStory"), icon: "film", tone: "pink" },
  { id: "drafts", label: t("nav.drafts"), icon: "file", tone: "warning" },
]);
const items = computed(() => mode.value === "creation" ? creationItems.value : mainItems.value);

onMounted(async () => {
  actor.value = await ContentService.currentActor().catch(() => null);
});

function navigate(id: string) {
  if (id === "create") {
    mode.value = mode.value === "creation" ? "main" : "creation";
    return;
  }
  mode.value = "main";
  if (id === "account") window.location.assign(accountHref.value);
  if (id === "profile") void router.push("/me");
  if (id === "feed") window.location.assign(contentUrl("/", true));
  if (id === "new-post") window.location.assign(contentUrl("/p/new", true));
  if (id === "new-story") window.location.assign(contentUrl("/story/new", true));
  if (id === "drafts") window.location.assign(contentUrl("/drafts", true));
}

function openSearch() {
  void router.push("/search");
}
</script>

<template>
  <div class="app-shell" data-onix-tone="neutral" data-onix-density="comfortable" data-onix-surface-depth="0">
    <div class="app-main" data-onix-app-content><slot /></div>
    <button class="search-fab" type="button" :aria-label="t('nav.search')" @click="openSearch">
      <OnixIcon name="search" :size="24" />
    </button>
    <GraphMenu :items="items" :mode="mode" :menu-label="t('nav.menu')" :close-label="t('nav.close')" @select="navigate" @close="mode = 'main'" />
  </div>
</template>

<style scoped>
.app-shell,
.app-main { min-height: 100dvh; background: var(--onix-color-surface-page); }
.search-fab {
  position: fixed;
  z-index: calc(var(--onix-layer-modal) + 1);
  inset-inline-start: max(var(--onix-space-4), env(safe-area-inset-left));
  inset-block-end: max(var(--onix-space-4), env(safe-area-inset-bottom));
  width: 3.25rem;
  min-height: 3.25rem;
  padding: 0;
  border: 0;
  border-radius: var(--onix-radius-pill);
  color: var(--onix-color-tone-info-on-solid);
  background: var(--onix-color-tone-info-solid);
  box-shadow: var(--onix-shadow-floating);
  cursor: pointer;
  pointer-events: auto;
  transition: transform var(--onix-motion-base), box-shadow var(--onix-motion-fast);
}
.search-fab:hover { transform: translateY(-2px); box-shadow: var(--onix-shadow-overlay); }
.search-fab:focus-visible { box-shadow: 0 0 0 4px var(--onix-color-focus-halo), var(--onix-shadow-floating); }
</style>
