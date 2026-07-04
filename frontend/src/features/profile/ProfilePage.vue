<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { apiErrorMessage } from "@/api/client";
import { ProfileService } from "@/api/profileService";
import type { AccountProfile, ProfileCanvasResponse, SessionUser } from "@/api/types";
import { runtimeConfig } from "@/runtime-config";
import { accountSettingsUrl } from "@/features/profile/accountLinks";
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
const accountSettingsHref = computed(() => accountSettingsUrl(runtimeConfig.accountFrontendUrl, window.location.href));
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
  response.value ? buildProfileCanvasLayout(response.value, viewportSize.value) : null
));
const canvasNodes = computed(() => canvasLayout.value?.nodes || []);
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

              <a
                v-else-if="node.id === 'settingsAction'"
                class="canvas-node node node-action node-settings-action"
                :style="nodeStyle(node)"
                :href="accountSettingsHref"
                aria-label="Open account settings"
                title="Settings"
              >
                <i class="pi pi-cog"></i>
                <span>Settings</span>
              </a>
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
