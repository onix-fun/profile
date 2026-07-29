<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { apiErrorMessage } from "@/shared/api/client";
import { ProfileService } from "@/shared/api/profileService";
import type { AccountProfile, RelatedUser, SocialFilter } from "@/shared/api/types";
import { withEmbedQuery } from "@/features/embed/lib/profileEmbed";
import {
  buildSocialCanvasLayout,
  type SocialCanvasSize,
  type SocialCanvasUserNode,
} from "@/features/profile/lib/socialCanvasLayout";
import OnixIcon from "@/shared/ui/OnixIcon.vue";

const route = useRoute();
const router = useRouter();

const owner = ref<AccountProfile | null>(null);
const users = ref<RelatedUser[]>([]);
const totalCount = ref(0);
const isLoading = ref(true);
const errorMessage = ref<string | null>(null);
const viewportElement = ref<HTMLElement | null>(null);
const viewportSize = ref<SocialCanvasSize>({ width: 1024, height: 620 });
let resizeObserver: ResizeObserver | null = null;

const filters: Array<{ key: SocialFilter; label: string }> = [
  { key: "friends", label: "Friends" },
  { key: "subscribers", label: "Subscribers" },
  { key: "subscriptions", label: "Subscriptions" },
];

const isOrganizationRoute = computed(() => route.name === "OrganizationSocialCanvas" || Boolean(route.params.orgname));
const ownerSlug = computed(() => String(isOrganizationRoute.value ? route.params.orgname || "" : route.params.nickname || ""));
const activeFilter = computed<SocialFilter>(() => normalizeFilter(route.query.filter));
const ownerName = computed(() => displayName(owner.value));
const ownerProfilePath = computed(() => owner.value ? profilePath(owner.value.ownerType || "USER", owner.value.username) : "/");
const layout = computed(() => owner.value ? buildSocialCanvasLayout(owner.value, users.value, viewportSize.value) : null);
const stageStyle = computed(() => {
  if (!layout.value) return {};
  return {
    width: `${layout.value.stage.width}px`,
    height: `${layout.value.stage.height}px`,
  };
});
const ownerStyle = computed(() => {
  const node = layout.value?.ownerNode;
  if (!node) return {};
  return {
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${node.width}px`,
    height: `${node.height}px`,
  };
});

watch(() => [route.params.nickname, route.params.orgname, route.query.filter], () => {
  void loadSocial();
}, { flush: "post" });

watch(viewportElement, (element) => {
  resizeObserver?.disconnect();
  resizeObserver = null;
  if (!element) return;
  updateViewportSize();
  resizeObserver = new ResizeObserver(() => {
    updateViewportSize();
    void nextTick(centerCanvas);
  });
  resizeObserver.observe(element);
  void nextTick(centerCanvas);
}, { flush: "post" });

watch(layout, async () => {
  await nextTick();
  centerCanvas();
}, { flush: "post" });

onMounted(() => {
  void loadSocial();
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
});

async function loadSocial() {
  isLoading.value = true;
  errorMessage.value = null;
  try {
    const response = isOrganizationRoute.value
      ? await ProfileService.getOrganizationSocial(ownerSlug.value, activeFilter.value, 1, 80)
      : await ProfileService.getSocial(ownerSlug.value, activeFilter.value, 1, 80);
    owner.value = response.owner;
    users.value = response.items;
    totalCount.value = response.totalCount;
  } catch (cause) {
    errorMessage.value = apiErrorMessage(cause);
    users.value = [];
    totalCount.value = 0;
  } finally {
    isLoading.value = false;
    await nextTick();
    updateViewportSize();
    centerCanvas();
  }
}

function selectFilter(filter: SocialFilter) {
  void router.replace({
    path: route.path,
    query: { ...route.query, filter },
  });
}

function userNodeStyle(node: SocialCanvasUserNode) {
  return {
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${node.width}px`,
    height: `${node.height}px`,
  };
}

function updateViewportSize() {
  const element = viewportElement.value;
  if (!element) return;
  viewportSize.value = {
    width: Math.max(320, element.clientWidth),
    height: Math.max(420, element.clientHeight),
  };
}

function centerCanvas() {
  const element = viewportElement.value;
  const currentLayout = layout.value;
  if (!element || !currentLayout) return;
  element.scrollLeft = currentLayout.initialScrollLeft;
}

function normalizeFilter(value: unknown): SocialFilter {
  return value === "subscribers" || value === "subscriptions" || value === "friends" ? value : "friends";
}

function displayName(user: Pick<RelatedUser, "displayName" | "firstName" | "lastName" | "username"> | AccountProfile | null): string {
  if (!user) return "Profile";
  return user.displayName || [user.firstName, user.lastName].filter(Boolean).join(" ") || user.username;
}

function profilePath(ownerType: "USER" | "ORGANIZATION", username: string): string {
  const prefix = ownerType === "ORGANIZATION" ? "o" : "u";
  return withEmbedQuery(route, `/${prefix}/${encodeURIComponent(username)}`);
}
</script>

<template>
  <main class="social-shell">
    <section v-if="errorMessage" class="social-state">
      <OnixIcon name="lock" :size="28" />
      <h1>Social unavailable</h1>
      <p>{{ errorMessage }}</p>
      <RouterLink :to="ownerProfilePath">Back to profile</RouterLink>
    </section>

    <section v-else class="social-canvas-shell">
      <div class="social-toolbar">
        <RouterLink class="social-back" :to="ownerProfilePath" aria-label="Back to profile">
          <OnixIcon name="arrow-left" :size="20" />
        </RouterLink>
        <div class="social-title">
          <strong>{{ ownerName }}</strong>
          <span>{{ totalCount }} {{ filters.find((item) => item.key === activeFilter)?.label || "Social" }}</span>
        </div>
        <div class="social-filters" role="tablist" aria-label="Social filter">
          <button
            v-for="filter in filters"
            :key="filter.key"
            type="button"
            :class="{ 'is-active': filter.key === activeFilter }"
            @click="selectFilter(filter.key)"
          >
            {{ filter.label }}
          </button>
        </div>
      </div>

      <div ref="viewportElement" class="social-viewport" aria-label="Social canvas">
        <div v-if="layout && owner" class="social-stage" :style="stageStyle">
          <RouterLink class="owner-node" :style="ownerStyle" :to="ownerProfilePath">
            <span class="owner-avatar">
              <img v-if="owner.avatarUrl" :src="owner.avatarUrl" alt="" />
              <OnixIcon v-else-if="owner.ownerType === 'ORGANIZATION'" name="building" :size="20" />
              <span v-else>{{ ownerName.slice(0, 2).toUpperCase() }}</span>
            </span>
            <strong>{{ ownerName }}</strong>
            <small>@{{ owner.username }}</small>
          </RouterLink>

          <RouterLink
            v-for="node in layout.userNodes"
            :key="`${node.user.ownerType || 'USER'}:${node.id}`"
            class="user-node"
            :class="{ 'user-node--organization': node.user.ownerType === 'ORGANIZATION' }"
            :style="userNodeStyle(node)"
            :to="profilePath(node.user.ownerType || 'USER', node.user.username)"
          >
            <span class="user-avatar">
              <img v-if="node.user.avatarUrl" :src="node.user.avatarUrl" alt="" />
              <OnixIcon v-else-if="node.user.ownerType === 'ORGANIZATION'" name="building" :size="20" />
              <span v-else>{{ displayName(node.user).slice(0, 2).toUpperCase() }}</span>
            </span>
            <span class="user-capsule">
              <strong>@{{ node.user.username }}</strong>
              <small>{{ displayName(node.user) }}</small>
            </span>
          </RouterLink>

          <p v-if="!isLoading && !users.length" class="social-empty">No users in this filter</p>
          <p v-if="isLoading" class="social-empty"><OnixIcon name="refresh" class="onix-icon--spin" :size="20" /> Loading social</p>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.social-shell,
.social-canvas-shell {
  min-height: 100dvh;
  background: var(--onix-color-surface-page);
}

.social-canvas-shell {
  position: relative;
  overflow: hidden;
}

.social-toolbar {
  position: fixed;
  z-index: 8;
  top: max(14px, env(safe-area-inset-top));
  left: 16px;
  right: 16px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  pointer-events: none;
}

.social-back,
.social-title,
.social-filters {
  pointer-events: auto;
}

.social-back {
  width: var(--onix-control-md);
  height: var(--onix-control-md);
  border-radius: var(--onix-radius-pill);
  display: grid;
  place-items: center;
  color: var(--onix-color-text);
  text-decoration: none;
  background: var(--onix-color-surface-floating);
  
}

.social-title {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.social-title strong,
.social-title span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.social-title strong {
  font-size: 16px;
  color: var(--onix-color-text);
  font-weight: 900;
}

.social-title span {
  color: var(--onix-color-text-muted);
  font-size: 12px;
  font-weight: 800;
}

.social-filters {
  display: inline-flex;
  gap: 4px;
  padding: 5px;
  border-radius: var(--onix-radius-pill);
  background: var(--onix-color-surface-floating);
  
  
}

.social-filters button {
  min-height: var(--onix-control-md);
  
  border-radius: var(--onix-radius-pill);
  padding: 0 12px;
  background: transparent;
  color: var(--onix-color-text-muted);
  font: inherit;
  font-size: 12px;
  font-weight: 900;
  cursor: pointer;
}

.social-filters button.is-active {
  background: var(--onix-color-text);
  color: var(--onix-color-surface-base);
}

.social-viewport {
  width: 100%;
  height: 100dvh;
  overflow: auto hidden;
  overscroll-behavior-x: contain;
  overscroll-behavior-y: none;
  scrollbar-width: thin;
  touch-action: pan-x;
}

.social-stage {
  position: relative;
  min-width: 100%;
  min-height: 100%;
}

.owner-node,
.user-node {
  position: absolute;
  box-sizing: border-box;
  text-decoration: none;
  color: var(--onix-color-text);
}

.owner-node {
  display: grid;
  place-items: center;
  align-content: center;
  gap: 5px;
  border-radius: var(--onix-radius-pill);
  background: var(--onix-color-surface-floating);
  
  
}

.owner-avatar {
  width: 82px;
  height: 82px;
  border-radius: var(--onix-radius-pill);
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--onix-color-surface-muted);
  font-size: 24px;
  font-weight: 900;
}

.owner-avatar img,
.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.owner-avatar i,
.user-avatar i {
  color: var(--onix-color-text-muted);
}

.owner-node strong {
  max-width: 118px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 900;
}

.owner-node small {
  color: var(--onix-color-text-muted);
  font-weight: 800;
}

.user-node {
  display: flex;
  align-items: center;
  gap: 0;
  filter: none;
}

.user-node--organization .user-avatar {
  background: var(--onix-color-tone-success-soft);
  color: var(--onix-color-tone-success-ink);
}

.user-node--organization .user-capsule {
  
}

.user-avatar {
  position: relative;
  z-index: 2;
  width: 66px;
  height: 66px;
  border-radius: var(--onix-radius-pill);
  display: grid;
  place-items: center;
  overflow: hidden;
  
  background: var(--onix-color-surface-muted);
  font-size: 18px;
  font-weight: 900;
}

.user-capsule {
  min-width: 154px;
  max-width: 170px;
  min-height: 50px;
  display: grid;
  justify-content: start;
  align-content: center;
  gap: 2px;
  margin-left: -8px;
  padding: 7px 13px 7px 18px;
  border-radius: var(--onix-radius-pill);
  background: var(--onix-color-surface-floating);
  
}

.user-capsule strong,
.user-capsule small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-capsule strong {
  font-size: 13px;
  font-weight: 900;
}

.user-capsule small {
  color: var(--onix-color-text-muted);
  font-size: var(--onix-font-size-caption);
  font-weight: 800;
}

.social-empty {
  position: absolute;
  left: 50%;
  top: calc(50% + 106px);
  margin: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transform: translateX(-50%);
  color: var(--onix-color-text-muted);
  font-weight: 900;
}

.social-state {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  padding: 28px;
  text-align: center;
}

.social-state i {
  font-size: 28px;
}

.social-state h1,
.social-state p {
  margin: 0;
}

.social-state p {
  color: var(--onix-color-text-muted);
  font-weight: 800;
}

.social-state a {
  color: var(--onix-color-text);
  font-weight: 900;
}

@media (max-width: 720px) {
  .social-toolbar {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .social-filters {
    grid-column: 1 / -1;
    justify-self: center;
  }
}
</style>
