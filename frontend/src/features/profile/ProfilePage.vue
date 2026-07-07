<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { apiErrorMessage } from "@/api/client";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import type { AccountProfile, ProfileCanvasResponse, ProfileContentPost, SessionUser } from "@/api/types";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import {
  buildProfileCanvasLayout,
  type PositionedProfileCanvasNode,
  type ProfileCanvasSize,
} from "@/features/profile/profileCanvasLayout";

const route = useRoute();
const router = useRouter();
const toast = useToast();

const isLoading = ref(true);
const errorCode = ref<string | null>(null);
const currentUser = ref<SessionUser | null>(null);
const response = ref<ProfileCanvasResponse | null>(null);
const archiveCount = ref(0);
const profile = computed<AccountProfile | null>(() => response.value?.profile || null);
const canvasViewport = ref<HTMLElement | null>(null);
const canvasElement = ref<HTMLCanvasElement | null>(null);
const viewportSize = ref<ProfileCanvasSize>({ width: 1024, height: 620 });
let resizeObserver: ResizeObserver | null = null;
let themeObserver: MutationObserver | null = null;
let drawFrame = 0;

const nickname = computed(() => String(route.params.nickname || ""));
const isBlocked = computed(() => response.value?.status === "BLOCKED");
const canFollow = computed(() => Boolean(response.value?.permissions.canFollow));
const relationship = computed(() => profile.value?.relationship || response.value?.relationship || null);
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
  }) : null
));
const canvasNodes = computed(() => canvasLayout.value?.nodes || []);
const profilePosts = computed(() => response.value?.content?.posts || []);
const canvasStageStyle = computed(() => {
  const stage = canvasLayout.value?.stage;
  if (!stage) return {};
  return {
    width: `${stage.width}px`,
    height: `${stage.height}px`,
  };
});

watch(() => route.params.nickname, () => loadProfile(), { flush: "post" });
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
    if (nickname.value === "me") {
      await router.replace(`/u/${currentUser.value.username}`);
      return;
    }

    response.value = await ProfileService.getProfile(nickname.value);
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
  if (!response.value?.profile?.id) return;
  const archive = await ContentService.storyArchive(response.value.profile.id, null, 12);
  archiveCount.value = archive.stories.length;
}

async function toggleFollow() {
  if (!profile.value || !canFollow.value || relationship.value?.hasPendingRequest) return;
  try {
    if (relationship.value?.isFollowing) {
      await ProfileService.unfollow(profile.value.id);
    } else {
      const next = await ProfileService.follow(profile.value.id);
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

function openPost(post: ProfileContentPost) {
  void router.push(`/p/${encodeURIComponent(post.id)}`);
}

function openArchive() {
  void router.push(`/u/${encodeURIComponent(nickname.value)}/stories/archive`);
}

async function togglePostLike(post: ProfileContentPost) {
  if (!response.value) return;
  try {
    const next = post.likedByViewer
      ? await ContentService.unlikePost(post.id)
      : await ContentService.likePost(post.id);
    const currentContent = response.value.content || { posts: [], stories: [], comments: [] };
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

    <section v-else-if="profile" class="canvas-shell">
      <div ref="canvasViewport" class="canvas-viewport" aria-label="Profile canvas">
        <div class="canvas-stage" :style="canvasStageStyle">
          <canvas ref="canvasElement" class="canvas-lines" aria-hidden="true"></canvas>
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

              <div
                v-else-if="node.id === 'socialLinks'"
                class="canvas-node node node-links"
                :style="nodeStyle(node)"
              >
                <a
                  v-for="link in socialLinks()"
                  :key="`${link.label}-${link.url}`"
                  :href="link.url"
                  target="_blank"
                  rel="noreferrer"
                >
                  <i class="pi pi-external-link"></i>
                  <span>{{ link.label }}</span>
                </a>
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
              />
            </template>
          </div>
        </div>
      </div>
    </section>

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
  gap: 6px;
  padding: 9px;
  align-content: start;
  overflow-y: auto;
}

.node-links a {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  padding: 7px 9px;
  border-radius: 8px;
  color: var(--text);
  text-decoration: none;
  background: var(--surface-muted);
  font-weight: 800;
  font-size: 13px;
}

.node-links span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
