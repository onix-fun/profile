<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { apiErrorMessage } from "@/api/client";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import type { AccountProfile, ContentBlock, ProfileCanvasResponse, ProfileContentPost, SavedCollection, SessionUser } from "@/api/types";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import SaveToCollectionsPopover from "@/features/content/SaveToCollectionsPopover.vue";
import {
  buildProfileCanvasLayout,
  type PositionedProfileCanvasNode,
  type ProfileCanvasSize,
} from "@/features/profile/profileCanvasLayout";
import { describeSocialLink } from "@/features/profile/socialLinks";

const route = useRoute();
const router = useRouter();
const toast = useToast();

const isLoading = ref(true);
const errorCode = ref<string | null>(null);
const currentUser = ref<SessionUser | null>(null);
const response = ref<ProfileCanvasResponse | null>(null);
const archiveCount = ref(0);
const activeMode = ref<"posts" | "collections">("posts");
const savingPostId = ref<string | null>(null);
const isCreatingCollection = ref(false);
const newCollectionTitle = ref("");
const newCollectionDescription = ref("");
const newCollectionVisibility = ref<"PUBLIC" | "PRIVATE">("PRIVATE");
const profile = computed<AccountProfile | null>(() => response.value?.profile || null);
const canvasViewport = ref<HTMLElement | null>(null);
const canvasElement = ref<HTMLCanvasElement | null>(null);
const viewportSize = ref<ProfileCanvasSize>({ width: 1024, height: 620 });
let resizeObserver: ResizeObserver | null = null;
let themeObserver: MutationObserver | null = null;
let drawFrame = 0;

const isOrganizationRoute = computed(() => route.name === "OrganizationProfile");
const nickname = computed(() => String(route.params.nickname || route.params.orgname || ""));
const isBlocked = computed(() => response.value?.status === "BLOCKED");
const isPrivateLocked = computed(() => response.value?.status === "PRIVATE");
const canFollow = computed(() => Boolean(response.value?.permissions.canFollow));
const relationship = computed(() => profile.value?.relationship || response.value?.relationship || null);
const displayName = computed(() => {
  const current = profile.value;
  if (!current) return "Profile";
  return current.displayName || [current.firstName, current.lastName].filter(Boolean).join(" ") || current.username;
});
const followLabel = computed(() => {
  const rel = relationship.value;
  if (!rel) return "Follow";
  if (rel.hasPendingRequest) return "Requested";
  if (rel.isFollowing) return "Following";
  return "Follow";
});
const followIcon = computed(() => {
  if (relationship.value?.hasPendingRequest) return "pi pi-clock";
  if (relationship.value?.isFollowing) return "pi pi-check";
  return "pi pi-user-plus";
});
const canvasLayout = computed(() => (
  response.value ? buildProfileCanvasLayout(response.value, viewportSize.value, {
    hasArchive: archiveCount.value > 0,
    archiveCount: archiveCount.value,
    mode: activeMode.value,
  }) : null
));
const canvasNodes = computed(() => canvasLayout.value?.nodes || []);
const profilePosts = computed(() => response.value?.content?.posts || []);
const profileCollections = computed(() => response.value?.content?.collections || []);
const isOwner = computed(() => Boolean(response.value?.permissions.owner));
const canvasStageStyle = computed<Record<string, string>>(() => {
  const stage = canvasLayout.value?.stage;
  if (!stage) return {} as Record<string, string>;
  return {
    width: `${stage.width}px`,
    height: `${stage.height}px`,
  };
});
const modeNavStyle = computed<Record<string, string>>(() => {
  const layout = canvasLayout.value;
  if (!layout) return {} as Record<string, string>;
  return {
    left: `${layout.avatarCenter.x + 86}px`,
    top: `${layout.avatarCenter.y - 92}px`,
  };
});

watch(() => [route.params.nickname, route.params.orgname, route.name], () => loadProfile(), { flush: "post" });
watch(canvasViewport, (element) => {
  resizeObserver?.disconnect();
  resizeObserver = null;

  if (!element) return;
  updateViewportSize();
  resizeObserver = new ResizeObserver(() => {
    updateViewportSize();
    scheduleCanvasDraw();
  });
  resizeObserver.observe(element);
  scheduleCanvasDraw();
  centerCanvas();
}, { flush: "post" });
watch(canvasLayout, async () => {
  await nextTick();
  scheduleCanvasDraw();
  centerCanvas();
}, { flush: "post" });

onMounted(() => {
  themeObserver = new MutationObserver(() => scheduleCanvasDraw());
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
  void loadProfile();
});
onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  themeObserver?.disconnect();
  if (drawFrame) window.cancelAnimationFrame(drawFrame);
});

async function loadProfile() {
  isLoading.value = true;
  errorCode.value = null;

  try {
    currentUser.value = await ProfileService.session();
    if (!isOrganizationRoute.value && nickname.value === "me") {
      const actor = await ContentService.currentActor().catch(() => null);
      if (actor?.activeOwner?.ownerType === "ORGANIZATION") {
        await router.replace(`/o/${encodeURIComponent(actor.activeOwner.username)}`);
        return;
      }
      if (currentUser.value) {
        await router.replace(`/u/${currentUser.value.username}`);
        return;
      }
    }

    response.value = isOrganizationRoute.value
      ? await ProfileService.getOrganization(nickname.value)
      : await ProfileService.getProfile(nickname.value);
    await loadArchiveStatus();
  } catch (cause: unknown) {
    const status = (cause as { response?: { status?: number } }).response?.status;
    errorCode.value = status === 404 ? "NOT_FOUND" : status === 403 ? "FORBIDDEN" : "FAILED";
  } finally {
    isLoading.value = false;
    await nextTick();
    updateViewportSize();
    scheduleCanvasDraw();
    centerCanvas();
  }
}

async function loadArchiveStatus() {
  archiveCount.value = 0;
  if (!response.value?.profile?.id || response.value.status !== "OK") return;
  if (!currentUser.value) return;
  const archive = await ContentService.storyArchive(
    response.value.profile.id,
    null,
    12,
    response.value.profile.ownerType || "USER",
  );
  archiveCount.value = archive.stories.length;
}

async function toggleFollow() {
  if (!profile.value || !canFollow.value || relationship.value?.hasPendingRequest) return;
  try {
    if (relationship.value?.isFollowing) {
      await ProfileService.unfollow(profile.value.id, profile.value.ownerType || "USER");
    } else {
      const next = await ProfileService.follow(profile.value.id, profile.value.ownerType || "USER");
      if (response.value?.profile) {
        response.value.profile.relationship = next;
      }
    }
    await loadProfile();
  } catch (cause) {
    toast.add({ severity: "error", summary: "Follow", detail: apiErrorMessage(cause), life: 5000 });
  }
}

function socialLinks() {
  return profile.value?.socialLinks || [];
}

function socialLinkViews() {
  return socialLinks().map(describeSocialLink);
}

function nodeStyle(node: PositionedProfileCanvasNode) {
  return {
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${node.width}px`,
    height: `${node.height}px`,
  };
}

function nodeLabel(node: PositionedProfileCanvasNode): string {
  return String(node.data.label ?? "");
}

function nodeCaption(node: PositionedProfileCanvasNode): string {
  return String(node.data.caption ?? "");
}

function nodeInitials(node: PositionedProfileCanvasNode): string {
  return String(node.data.initials ?? "?");
}

function nodeAvatarUrl(node: PositionedProfileCanvasNode): string {
  return String(node.data.avatarUrl ?? "");
}

function nodePost(node: PositionedProfileCanvasNode): ProfileContentPost | undefined {
  const postId = String(node.data.postId || "");
  return profilePosts.value.find((post) => post.id === postId);
}

function nodeCollection(node: PositionedProfileCanvasNode): SavedCollection | undefined {
  const collectionId = String(node.data.collectionId || "");
  return profileCollections.value.find((collection) => collection.id === collectionId);
}

function openPost(post: ProfileContentPost) {
  void router.push(`/p/${encodeURIComponent(post.id)}`);
}

function openSave(post: ProfileContentPost) {
  savingPostId.value = post.id;
}

function openCollection(collection: SavedCollection) {
  const prefix = isOrganizationRoute.value ? "o" : "u";
  void router.push(`/${prefix}/${encodeURIComponent(nickname.value)}/collections/${encodeURIComponent(collection.id)}`);
}

function collectionPreviewSource(block: ContentBlock): string {
  return ContentService.mediaSource(block);
}

async function createCollection() {
  if (!newCollectionTitle.value.trim() || !response.value) return;
  try {
    const created = await ContentService.createCollection({
      title: newCollectionTitle.value,
      description: newCollectionDescription.value,
      visibility: newCollectionVisibility.value,
    });
    const currentContent = response.value.content || { posts: [], stories: [], comments: [], collections: [] };
    response.value = {
      ...response.value,
      content: {
        ...currentContent,
        collections: [created, ...(currentContent.collections || [])],
      },
    };
    newCollectionTitle.value = "";
    newCollectionDescription.value = "";
    newCollectionVisibility.value = "PRIVATE";
    isCreatingCollection.value = false;
    activeMode.value = "collections";
  } catch (cause) {
    toast.add({ severity: "error", summary: "Collection", detail: apiErrorMessage(cause), life: 5000 });
  }
}

function openArchive() {
  const prefix = isOrganizationRoute.value ? "o" : "u";
  void router.push(`/${prefix}/${encodeURIComponent(nickname.value)}/stories/archive`);
}

function openSocial() {
  const prefix = isOrganizationRoute.value ? "o" : "u";
  void router.push(`/${prefix}/${encodeURIComponent(nickname.value)}/social?filter=friends`);
}

async function togglePostLike(post: ProfileContentPost) {
  if (!response.value) return;
  try {
    const next = post.likedByViewer
      ? await ContentService.unlikePost(post.id)
      : await ContentService.likePost(post.id);
    const currentContent = response.value.content || { posts: [], stories: [], comments: [], collections: [] };
    response.value = {
      ...response.value,
      content: {
        ...currentContent,
        posts: currentContent.posts.map((item) => item.id === post.id
          ? { ...item, likedByViewer: next.liked, likeCount: next.likeCount }
          : item),
      },
    };
  } catch (cause) {
    toast.add({ severity: "error", summary: "Like", detail: apiErrorMessage(cause), life: 5000 });
  }
}

function updateViewportSize() {
  const element = canvasViewport.value;
  if (!element) return;
  viewportSize.value = {
    width: Math.max(320, element.clientWidth),
    height: Math.max(420, element.clientHeight),
  };
}

function centerCanvas() {
  const element = canvasViewport.value;
  const layout = canvasLayout.value;
  if (!element || !layout) return;
  element.scrollLeft = layout.initialScrollLeft;
}

function scheduleCanvasDraw() {
  if (drawFrame) window.cancelAnimationFrame(drawFrame);
  drawFrame = window.requestAnimationFrame(() => {
    drawFrame = 0;
    drawCanvas();
  });
}

function drawCanvas() {
  const canvas = canvasElement.value;
  const layout = canvasLayout.value;
  if (!canvas || !layout) return;

  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.round(layout.stage.width * ratio);
  canvas.height = Math.round(layout.stage.height * ratio);
  canvas.style.width = `${layout.stage.width}px`;
  canvas.style.height = `${layout.stage.height}px`;

  const context = canvas.getContext("2d");
  if (!context) return;

  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  context.clearRect(0, 0, layout.stage.width, layout.stage.height);

  const styles = getComputedStyle(document.documentElement);
  const stroke = styles.getPropertyValue("--surface-active").trim() || "#cbd5e1";
  const muted = styles.getPropertyValue("--muted").trim() || "#64748b";

  context.lineWidth = 1.7;
  context.lineCap = "round";
  context.strokeStyle = stroke;

  for (const edge of layout.edges) {
    const source = edge.sourcePoint;
    const target = edge.targetPoint;
    const dx = target.x - source.x;
    const dy = target.y - source.y;
    const curve = 0.08;
    const control = {
      x: source.x + dx * 0.55 - dy * curve,
      y: source.y + dy * 0.55 + dx * curve,
    };

    context.beginPath();
    context.moveTo(source.x, source.y);
    context.quadraticCurveTo(control.x, control.y, target.x, target.y);
    context.stroke();
  }

  context.fillStyle = muted;
  context.globalAlpha = 0.24;
  context.beginPath();
  context.arc(layout.avatarCenter.x, layout.avatarCenter.y, 76, 0, Math.PI * 2);
  context.stroke();
  context.globalAlpha = 1;
}
</script>

<template>
  <main class="profile-shell">
    <section v-if="isLoading" class="state-screen">
      <div class="loader" aria-hidden="true"></div>
      <span>Loading profile</span>
    </section>

    <section v-else-if="errorCode === 'NOT_FOUND'" class="state-screen">
      <i class="pi pi-search"></i>
      <h1>Profile not found</h1>
      <p>The username does not exist or is no longer available.</p>
    </section>

    <section v-else-if="isBlocked" class="state-screen">
      <i class="pi pi-ban"></i>
      <h1>Profile unavailable</h1>
      <p>This relationship blocks profile viewing.</p>
    </section>

    <section v-else-if="isPrivateLocked && profile" class="private-screen">
      <div class="private-card">
        <span class="private-avatar">
          <img v-if="profile.avatarUrl" :src="profile.avatarUrl" alt="" />
          <span v-else>{{ displayName.slice(0, 2).toUpperCase() }}</span>
        </span>
        <i class="pi pi-lock private-lock" aria-hidden="true"></i>
        <h1>{{ displayName }}</h1>
        <p>@{{ profile.username }}</p>
        <strong>Private profile</strong>
        <button
          type="button"
          class="private-follow"
          :disabled="!canFollow || relationship?.hasPendingRequest"
          @click="toggleFollow"
        >
          <i :class="followIcon"></i>
          <span>{{ relationship?.hasPendingRequest ? "Requested" : "Request access" }}</span>
        </button>
      </div>
    </section>

    <section v-else-if="profile" class="canvas-shell">
      <div ref="canvasViewport" class="canvas-viewport" aria-label="Profile canvas">
        <div class="canvas-stage" :style="canvasStageStyle">
          <canvas ref="canvasElement" class="canvas-lines" aria-hidden="true"></canvas>
          <nav class="profile-mode-nav" :style="modeNavStyle" aria-label="Profile sections">
            <button
              type="button"
              :class="{ 'is-active': activeMode === 'posts' }"
              title="Posts"
              @click="activeMode = 'posts'"
            >
              <i class="pi pi-th-large"></i>
            </button>
            <button
              type="button"
              :class="{ 'is-active': activeMode === 'collections' }"
              title="Collections"
              @click="activeMode = 'collections'"
            >
              <i class="pi pi-bookmark"></i>
            </button>
            <button
              v-if="isOwner && activeMode === 'collections'"
              type="button"
              class="profile-mode-nav__create"
              title="Create collection"
              @click="isCreatingCollection = !isCreatingCollection"
            >
              <i class="pi pi-plus"></i>
            </button>
          </nav>

          <form
            v-if="isOwner && isCreatingCollection"
            class="collection-create-card"
            :style="{ left: modeNavStyle.left, top: `calc(${String(modeNavStyle.top || '0px')} + 150px)` }"
            @submit.prevent="createCollection"
          >
            <input v-model="newCollectionTitle" maxlength="80" placeholder="Collection name" />
            <textarea v-model="newCollectionDescription" maxlength="280" rows="2" placeholder="Description"></textarea>
            <span>
              <button
                type="button"
                :class="{ 'is-active': newCollectionVisibility === 'PRIVATE' }"
                @click="newCollectionVisibility = 'PRIVATE'"
              >
                <i class="pi pi-lock"></i>
              </button>
              <button
                type="button"
                :class="{ 'is-active': newCollectionVisibility === 'PUBLIC' }"
                @click="newCollectionVisibility = 'PUBLIC'"
              >
                <i class="pi pi-globe"></i>
              </button>
              <button type="submit" :disabled="!newCollectionTitle.trim()">
                <i class="pi pi-check"></i>
              </button>
            </span>
          </form>

          <div class="canvas-layer">
            <template v-for="node in canvasNodes" :key="node.id">
              <div
                v-if="node.id === 'avatar'"
                class="canvas-node node node-avatar"
                :style="nodeStyle(node)"
              >
                <img v-if="nodeAvatarUrl(node)" :src="nodeAvatarUrl(node)" alt="" />
                <span v-else>{{ nodeInitials(node) }}</span>
              </div>

              <div
                v-else-if="node.type === 'label'"
                class="canvas-node node node-label"
                :class="`node-${node.id}`"
                :style="nodeStyle(node)"
              >
                {{ nodeLabel(node) }}
              </div>

              <div
                v-else-if="node.type === 'text'"
                class="canvas-node node node-text"
                :style="nodeStyle(node)"
              >
                {{ nodeLabel(node) }}
              </div>

              <div
                v-else-if="node.type === 'stat'"
                class="canvas-node node node-stat"
                :class="`node-${node.id}`"
                :style="nodeStyle(node)"
              >
                <strong>{{ nodeLabel(node) }}</strong>
                <span>{{ nodeCaption(node) }}</span>
              </div>

              <button
                v-else-if="node.id === 'social'"
                class="canvas-node node node-social"
                type="button"
                :style="nodeStyle(node)"
                @click="openSocial"
              >
                <i class="pi pi-users"></i>
                <strong>{{ nodeLabel(node) || "Social" }}</strong>
                <span>{{ String(node.data.subscribersLabel || "Subscribers") }}</span>
                <span>{{ String(node.data.subscriptionsLabel || "Subscriptions") }}</span>
              </button>

              <div
                v-else-if="node.id === 'socialLinks'"
                class="canvas-node node node-links"
                :style="nodeStyle(node)"
              >
                <div class="links-hub">
                  <i class="pi pi-link"></i>
                  <span>Links</span>
                </div>
                <div class="links-branches">
                <a
                  v-for="link in socialLinkViews()"
                  :key="`${link.label}-${link.url}`"
                  class="link-branch"
                  :style="{ '--link-color': link.meta.color }"
                  :href="link.href"
                  target="_blank"
                  rel="noreferrer"
                >
                  <span class="link-branch-icon">{{ link.meta.glyph }}</span>
                  <span class="link-branch-copy">
                    <strong>{{ link.label }}</strong>
                    <small>{{ link.displayUrl }}</small>
                  </span>
                </a>
                </div>
              </div>

              <button
                v-else-if="node.id === 'followAction'"
                class="canvas-node node node-action"
                type="button"
                :style="nodeStyle(node)"
                :disabled="!canFollow || relationship?.hasPendingRequest"
                @click="toggleFollow"
              >
                <i :class="followIcon"></i>
                <span>{{ followLabel }}</span>
              </button>

              <button
                v-else-if="node.id === 'archive'"
                class="canvas-node node node-archive"
                type="button"
                :style="nodeStyle(node)"
                @click="openArchive"
              >
                <i class="pi pi-history"></i>
                <strong>{{ nodeLabel(node) }}</strong>
                <span>{{ nodeCaption(node) }}</span>
              </button>

              <PostCloudNode
                v-else-if="node.type === 'post' && nodePost(node)"
                class="canvas-node node node-post"
                :style="nodeStyle(node)"
                :post="nodePost(node)!"
                mode="profile"
                @open="openPost(nodePost(node)!)"
                @comments="openPost(nodePost(node)!)"
                @like="togglePostLike(nodePost(node)!)"
                @bookmark="openSave(nodePost(node)!)"
              />

              <button
                v-else-if="node.type === 'collection' && nodeCollection(node)"
                class="canvas-node node node-collection"
                type="button"
                :style="nodeStyle(node)"
                @click="openCollection(nodeCollection(node)!)"
              >
                <span class="collection-preview" :class="{ 'is-empty': !nodeCollection(node)!.previewBlocks.length }">
                  <template v-if="nodeCollection(node)!.previewBlocks.length">
                    <img
                      v-for="block in nodeCollection(node)!.previewBlocks.slice(0, 3)"
                      :key="block.id || String(block.data.blobId || block.data.src || block.data.url)"
                      :src="collectionPreviewSource(block)"
                      alt=""
                    />
                  </template>
                  <i v-else class="pi pi-bookmark"></i>
                </span>
                <strong>{{ nodeCollection(node)!.title }}</strong>
                <small>{{ nodeCollection(node)!.description || `${nodeCollection(node)!.itemCount} posts` }}</small>
                <i v-if="isOwner" :class="nodeCollection(node)!.visibility === 'PUBLIC' ? 'pi pi-globe' : 'pi pi-lock'"></i>
              </button>
            </template>
          </div>
        </div>
      </div>
    </section>

    <SaveToCollectionsPopover
      v-if="savingPostId"
      :post-id="savingPostId"
      @close="savingPostId = null"
    />

    <section v-else class="state-screen">
      <i class="pi pi-exclamation-triangle"></i>
      <h1>Unable to load profile</h1>
      <p>Try again later.</p>
    </section>
  </main>
</template>

<style scoped>
.profile-shell {
  min-height: 100dvh;
  background: var(--bg);
}

.canvas-shell {
  position: relative;
  min-height: 0;
  overflow: hidden;
}

.canvas-viewport {
  width: 100%;
  height: 100dvh;
  overflow-x: auto;
  overflow-y: hidden;
  overscroll-behavior-x: contain;
  overscroll-behavior-y: none;
  scrollbar-width: thin;
  touch-action: pan-x;
}

.canvas-stage {
  position: relative;
  min-width: 100%;
  min-height: 100%;
}

.canvas-lines,
.canvas-layer {
  position: absolute;
  inset: 0;
}

.canvas-lines {
  pointer-events: none;
}

.canvas-layer {
  z-index: 1;
}

.profile-mode-nav {
  position: absolute;
  z-index: 6;
  width: 92px;
  height: 176px;
  pointer-events: none;
}

.profile-mode-nav button {
  position: absolute;
  width: 42px;
  height: 42px;
  border: 1px solid var(--surface-active);
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-raised);
  color: var(--muted);
  box-shadow: 0 12px 34px rgba(15, 23, 42, 0.14);
  cursor: pointer;
  pointer-events: auto;
  transition: transform 180ms ease, background 180ms ease, color 180ms ease;
}

.profile-mode-nav button:nth-child(1) {
  left: 0;
  top: 0;
}

.profile-mode-nav button:nth-child(2) {
  left: 34px;
  top: 62px;
}

.profile-mode-nav__create {
  left: 0;
  top: 124px;
}

.profile-mode-nav button:hover,
.profile-mode-nav button.is-active {
  transform: translateX(5px) scale(1.04);
  background: var(--text);
  color: var(--surface);
}

.collection-create-card {
  position: absolute;
  z-index: 7;
  width: 260px;
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--surface-active);
  border-radius: 12px;
  background: var(--surface-raised);
  box-shadow: 0 18px 54px rgba(15, 23, 42, 0.18);
}

.collection-create-card input,
.collection-create-card textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--surface-active);
  border-radius: 9px;
  padding: 9px 10px;
  background: var(--surface);
  color: var(--text);
  font: inherit;
  resize: none;
}

.collection-create-card span {
  display: inline-flex;
  justify-content: flex-end;
  gap: 6px;
}

.collection-create-card button {
  width: 34px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-muted);
  color: var(--muted);
  cursor: pointer;
}

.collection-create-card button.is-active,
.collection-create-card button[type="submit"] {
  background: var(--text);
  color: var(--surface);
}

.collection-create-card button:disabled {
  cursor: default;
  opacity: 0.5;
}

.canvas-node {
  position: absolute;
  z-index: 2;
  box-sizing: border-box;
}

.node {
  border: 1px solid var(--surface-active);
  background: var(--surface-raised);
  color: var(--text);
  box-shadow: 0 10px 26px rgba(22, 34, 51, 0.12);
  user-select: none;
}

.node-avatar {
  width: 132px;
  height: 132px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 4px solid var(--surface);
  font-size: 36px;
  font-weight: 900;
}

.node-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.node-label,
.node-text,
.node-stat,
.node-social,
.node-archive,
.node-links,
.node-action {
  border-radius: 12px;
  overflow: hidden;
}

.node-label {
  padding: 12px 16px;
  text-align: center;
  font-size: 18px;
  font-weight: 900;
  display: grid;
  place-items: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-text {
  padding: 14px 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--muted);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  line-height: 1.35;
}

.node-stat {
  padding: 12px 16px;
  display: grid;
  text-align: center;
  gap: 2px;
}

.node-stat strong {
  font-size: 22px;
  line-height: 1;
  font-weight: 900;
}

.node-stat span {
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.node-social {
  border: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  align-items: center;
  gap: 3px 10px;
  padding: 13px 14px;
  background: var(--surface-raised);
  color: var(--text);
  text-align: left;
  cursor: pointer;
}

.node-social i {
  grid-row: 1 / span 3;
  width: 34px;
  height: 34px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-muted);
  color: var(--muted);
}

.node-social strong {
  font-size: 17px;
  line-height: 1;
  font-weight: 900;
}

.node-social span {
  color: var(--muted);
  font-size: 11px;
  font-weight: 900;
}

.node-archive {
  border: 0;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 2px 10px;
  align-items: center;
  padding: 12px 14px;
  background: linear-gradient(135deg, rgba(17, 24, 39, 0.96), rgba(34, 197, 94, 0.82));
  color: #ffffff;
  text-align: left;
  cursor: pointer;
}

.node-archive i {
  grid-row: 1 / span 2;
  font-size: 19px;
}

.node-archive strong {
  font-size: 20px;
  font-weight: 900;
  line-height: 1;
}

.node-archive span {
  color: rgba(255, 255, 255, 0.74);
  font-size: 12px;
  font-weight: 900;
}

.node-links {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 10px;
  padding: 11px;
  align-items: center;
  overflow-y: auto;
}

.links-hub {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  background: var(--surface-muted);
  color: var(--muted);
  display: grid;
  place-items: center;
  align-content: center;
  gap: 3px;
  font-size: 18px;
  font-weight: 900;
}

.links-hub span {
  max-width: 100%;
  color: var(--muted);
  font-size: 10px;
  line-height: 1;
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.links-branches {
  position: relative;
  min-width: 0;
  display: grid;
  gap: 7px;
  padding-left: 15px;
}

.links-branches::before {
  content: "";
  position: absolute;
  left: 3px;
  top: 18px;
  bottom: 18px;
  width: 2px;
  border-radius: 999px;
  background: var(--surface-strong);
}

.link-branch {
  position: relative;
  min-width: 0;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  padding: 6px 8px 6px 6px;
  border-radius: 11px;
  color: var(--text);
  text-decoration: none;
  background: var(--surface-muted);
  transition: transform 160ms ease, background 180ms ease;
}

.link-branch::before {
  content: "";
  position: absolute;
  left: -12px;
  top: 50%;
  width: 12px;
  height: 2px;
  border-radius: 999px;
  background: var(--link-color);
  transform: translateY(-50%);
}

.link-branch:hover {
  transform: translateX(2px);
  background: var(--surface-active);
}

.link-branch-icon {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: color-mix(in srgb, var(--link-color) 14%, transparent);
  color: var(--link-color);
  font-size: 10px;
  line-height: 1;
  font-weight: 900;
  letter-spacing: 0;
}

.link-branch-copy {
  min-width: 0;
  display: grid;
  gap: 1px;
}

.link-branch-copy strong,
.link-branch-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link-branch-copy strong {
  color: var(--text);
  font-size: 12px;
  line-height: 1.1;
  font-weight: 900;
}

.link-branch-copy small {
  color: var(--muted);
  font-size: 10px;
  line-height: 1.15;
  font-weight: 800;
}

.node-action {
  padding: 11px 16px;
  border: 0;
  display: inline-flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  cursor: pointer;
  font-weight: 900;
}

.node-settings-action {
  text-decoration: none;
}

.node-action:disabled {
  cursor: default;
  opacity: 0.7;
}

.node-post {
  border: 0;
  background: transparent;
  box-shadow: none;
}

.node-collection {
  border: 0;
  display: grid;
  grid-template-rows: 72px auto auto;
  gap: 5px;
  padding: 10px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.94);
  color: #111827;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
}

.node-collection > i {
  position: absolute;
  right: 10px;
  top: 10px;
  width: 24px;
  height: 24px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: rgba(17, 24, 39, 0.76);
  color: #ffffff;
  font-size: 11px;
}

.collection-preview {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 3px;
  overflow: hidden;
  border-radius: 12px;
  background: linear-gradient(135deg, #111827, #0f766e);
}

.collection-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.collection-preview img:first-child {
  grid-row: 1 / span 2;
}

.collection-preview.is-empty {
  display: grid;
  place-items: center;
  color: #ffffff;
  font-size: 24px;
}

.node-collection strong,
.node-collection small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-collection strong {
  font-size: 16px;
  line-height: 1.1;
  font-weight: 900;
}

.node-collection small {
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
}

.state-screen {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 10px;
  padding: 32px;
  text-align: center;
  color: var(--muted);
}

.state-screen i {
  font-size: 28px;
  color: var(--text);
}

.state-screen h1 {
  margin: 0;
  color: var(--text);
  font-size: 24px;
  line-height: 1.2;
}

.state-screen p,
.state-screen span {
  margin: 0;
  font-weight: 700;
}

.private-screen {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 28px;
  background: var(--bg);
}

.private-card {
  width: min(100%, 340px);
  display: grid;
  justify-items: center;
  gap: 9px;
  padding: 28px;
  border: 1px solid var(--surface-active);
  border-radius: 18px;
  background: var(--surface-raised);
  color: var(--text);
  text-align: center;
  box-shadow: 0 18px 46px rgba(22, 34, 51, 0.12);
}

.private-avatar {
  width: 108px;
  height: 108px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--surface-muted);
  border: 4px solid var(--surface);
  font-size: 30px;
  font-weight: 900;
}

.private-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.private-lock {
  width: 34px;
  height: 34px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-muted);
  color: var(--muted);
}

.private-card h1 {
  margin: 4px 0 0;
  font-size: 24px;
  line-height: 1.15;
}

.private-card p {
  margin: 0;
  color: var(--muted);
  font-weight: 800;
}

.private-card strong {
  margin-top: 4px;
  color: var(--muted);
  font-size: 13px;
}

.private-follow {
  margin-top: 8px;
  min-height: 42px;
  border: 0;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font: inherit;
  font-weight: 900;
  cursor: pointer;
}

.private-follow:disabled {
  cursor: default;
  opacity: 0.72;
}

.loader {
  width: 34px;
  height: 34px;
  border-radius: 999px;
  border: 3px solid transparent;
  border-top-color: var(--text);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

</style>
