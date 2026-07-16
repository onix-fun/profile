<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import { contentUrl } from "@/api/navigation";
import type { CollectionDetail, ContentBlock, ProfileContentPost } from "@/api/types";
import PostCloudNode from "@/features/content/PostCloudNode.vue";
import SaveToCollectionsPopover from "@/features/content/SaveToCollectionsPopover.vue";
import { postEmbedNavigation, withEmbedQuery } from "@/features/embed/profileEmbed";
import {
  buildCollectionCanvasLayout,
  type CollectionCanvasSize,
  type CollectionPostNode,
} from "@/features/profile/collectionCanvasLayout";

const route = useRoute();
const router = useRouter();
const toast = useToast();

const detail = ref<CollectionDetail | null>(null);
const isLoading = ref(true);
const errorMessage = ref("");
const viewportElement = ref<HTMLElement | null>(null);
const viewportSize = ref<CollectionCanvasSize>({ width: 1024, height: 620 });
const savingPostId = ref<string | null>(null);
let resizeObserver: ResizeObserver | null = null;

const collectionId = computed(() => String(route.params.collectionId || ""));
const isOrganizationRoute = computed(() => route.name === "OrganizationCollectionCanvas");
const ownerSlug = computed(() => String(isOrganizationRoute.value ? route.params.orgname || "" : route.params.nickname || ""));
const ownerPath = computed(() => withEmbedQuery(route, `/${isOrganizationRoute.value ? "o" : "u"}/${encodeURIComponent(ownerSlug.value)}`));
const layout = computed(() => detail.value ? buildCollectionCanvasLayout(detail.value.collection, detail.value.posts, viewportSize.value) : null);
const stageStyle = computed(() => layout.value ? {
  width: `${layout.value.stage.width}px`,
  height: `${layout.value.stage.height}px`,
} : {});
const centerStyle = computed(() => layout.value ? {
  left: `${layout.value.centerNode.x}px`,
  top: `${layout.value.centerNode.y}px`,
  width: `${layout.value.centerNode.width}px`,
  height: `${layout.value.centerNode.height}px`,
} : {});

onMounted(async () => {
  await loadCollection();
  await nextTick();
  updateViewportSize();
  resizeObserver = new ResizeObserver(() => {
    updateViewportSize();
    void nextTick(centerCanvas);
  });
  if (viewportElement.value) resizeObserver.observe(viewportElement.value);
  centerCanvas();
});

onBeforeUnmount(() => resizeObserver?.disconnect());

async function loadCollection() {
  isLoading.value = true;
  errorMessage.value = "";
  try {
    detail.value = await ContentService.collection(collectionId.value);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : "Unable to load collection";
  } finally {
    isLoading.value = false;
  }
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
  if (!element || !layout.value) return;
  element.scrollLeft = layout.value.initialScrollLeft;
}

function nodeStyle(node: CollectionPostNode) {
  return {
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${node.width}px`,
    height: `${node.height}px`,
  };
}

function openPost(post: ProfileContentPost) {
  if (postEmbedNavigation(route, { serviceKey: "content", path: `/p/${encodeURIComponent(post.id)}`, url: contentUrl(`/p/${encodeURIComponent(post.id)}`, true) })) return;
  window.location.assign(contentUrl(`/p/${encodeURIComponent(post.id)}`, true));
}

async function togglePostLike(post: ProfileContentPost) {
  if (!detail.value) return;
  try {
    const next = post.likedByViewer
      ? await ContentService.unlikePost(post.id)
      : await ContentService.likePost(post.id);
    detail.value = {
      ...detail.value,
      posts: detail.value.posts.map((item) => item.id === post.id
        ? { ...item, likedByViewer: next.liked, likeCount: next.likeCount }
        : item),
    };
  } catch (error) {
    toast.add({ severity: "error", summary: "Like", detail: error instanceof Error ? error.message : "Unable to update like", life: 5000 });
  }
}

function previewSource(block: ContentBlock): string {
  return ContentService.mediaSource(block);
}
</script>

<template>
  <main class="collection-canvas-shell">
    <RouterLink class="collection-back" :to="ownerPath" aria-label="Back to profile">
      <i class="pi pi-arrow-left"></i>
    </RouterLink>

    <section v-if="isLoading" class="collection-state">Loading collection</section>
    <section v-else-if="errorMessage" class="collection-state collection-state-panel">
      <i class="pi pi-lock"></i>
      <strong>Collection unavailable</strong>
      <span>{{ errorMessage }}</span>
    </section>

    <div v-else-if="detail && layout" ref="viewportElement" class="collection-viewport">
      <div class="collection-stage" :style="stageStyle">
        <article class="collection-center" :style="centerStyle">
          <span class="collection-center__preview" :class="{ 'is-empty': !detail.collection.previewBlocks.length }">
            <template v-if="detail.collection.previewBlocks.length">
              <img
                v-for="block in detail.collection.previewBlocks.slice(0, 3)"
                :key="block.id || String(block.data.blobId || block.data.src || block.data.url)"
                :src="previewSource(block)"
                alt=""
              />
            </template>
            <i v-else class="pi pi-bookmark"></i>
          </span>
          <strong>{{ detail.collection.title }}</strong>
          <p>{{ detail.collection.description || `${detail.collection.itemCount} saved posts` }}</p>
        </article>

        <PostCloudNode
          v-for="node in layout.postNodes"
          :key="node.id"
          class="collection-post"
          :style="nodeStyle(node)"
          :post="node.post"
          mode="profile"
          @open="openPost(node.post)"
          @comments="openPost(node.post)"
          @like="togglePostLike(node.post)"
          @bookmark="savingPostId = node.post.id"
        />
      </div>
    </div>

    <SaveToCollectionsPopover
      v-if="savingPostId"
      :post-id="savingPostId"
      @close="savingPostId = null"
    />
  </main>
</template>

<style scoped>
.collection-canvas-shell {
  min-height: 100dvh;
  background: var(--bg);
}

.collection-back {
  position: fixed;
  z-index: 9;
  top: max(14px, env(safe-area-inset-top));
  left: 16px;
  width: 42px;
  height: 42px;
  border: 1px solid var(--surface-active);
  border-radius: 999px;
  display: grid;
  place-items: center;
  background: var(--surface-raised);
  color: var(--text);
  text-decoration: none;
  box-shadow: 0 12px 34px rgba(15, 23, 42, 0.12);
}

.collection-viewport {
  width: 100%;
  height: 100dvh;
  overflow-x: auto;
  overflow-y: hidden;
  touch-action: pan-x;
}

.collection-stage {
  position: relative;
  min-width: 100%;
  min-height: 100%;
  background-image:
    radial-gradient(circle, rgba(71, 85, 105, 0.14) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.1) 1px, transparent 1px);
  background-size: 24px 24px, 180px 180px;
}

.collection-center,
.collection-post {
  position: absolute;
}

.collection-center {
  display: grid;
  grid-template-rows: 104px auto 1fr;
  gap: 8px;
  padding: 14px;
  border: 1px solid var(--surface-active);
  border-radius: 14px;
  background: var(--surface-raised);
  color: var(--text);
  box-shadow: 0 24px 72px rgba(15, 23, 42, 0.16);
  overflow: hidden;
}

.collection-center__preview {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 4px;
  overflow: hidden;
  border-radius: 12px;
  background: linear-gradient(135deg, #111827, #0f766e);
}

.collection-center__preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.collection-center__preview img:first-child {
  grid-row: 1 / span 2;
}

.collection-center__preview.is-empty {
  display: grid;
  place-items: center;
  color: #ffffff;
  font-size: 32px;
}

.collection-center strong,
.collection-center p {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

.collection-center strong {
  white-space: nowrap;
  font-size: 21px;
  font-weight: 900;
}

.collection-center p {
  display: -webkit-box;
  color: var(--muted);
  font-size: 13px;
  font-weight: 750;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.collection-state {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: var(--muted);
  font-weight: 900;
}

.collection-state-panel {
  align-content: center;
  gap: 8px;
  text-align: center;
}
</style>
