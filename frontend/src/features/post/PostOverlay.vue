<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { CommentItem, ContentBlock, FeedItem } from "@/api/types";

const route = useRoute();
const router = useRouter();
const toast = useToast();
const post = ref<FeedItem["post"] | null>(null);
const comments = ref<CommentItem[]>([]);
const commentText = ref("");
const replyDraft = ref<Record<string, string>>({});
const isLoading = ref(true);

const postId = computed(() => String(route.params.postId || ""));

onMounted(async () => {
  await loadPost();
  void ContentService.recordView(postId.value, 0).catch(() => undefined);
});

async function loadPost() {
  isLoading.value = true;
  try {
    post.value = await ContentService.post(postId.value);
    comments.value = await ContentService.comments(postId.value);
  } finally {
    isLoading.value = false;
  }
}

function close() {
  void router.push("/");
}

function blockText(block: ContentBlock): string {
  const value = block.data.text;
  return typeof value === "string" ? value : "";
}

function blockSource(block: ContentBlock): string {
  return ContentService.mediaSource(block);
}

async function sendComment(parentId?: string) {
  const text = (parentId ? replyDraft.value[parentId] : commentText.value).trim();
  if (!text) return;
  try {
    const created = await ContentService.createComment({ postId: postId.value, parentId, text });
    const item: CommentItem = { ...created, postId: postId.value, parentId, text };
    if (parentId) {
      comments.value = comments.value.map((comment) => comment.id === parentId
        ? { ...comment, replies: [...(comment.replies || []), item] }
        : comment);
      replyDraft.value[parentId] = "";
    } else {
      comments.value = [...comments.value, { ...item, replies: [] }];
      commentText.value = "";
    }
  } catch (error) {
    toast.add({ severity: "error", summary: "Comment", detail: error instanceof Error ? error.message : "Unable to comment", life: 5000 });
  }
}
</script>

<template>
  <section class="post-overlay" role="dialog" aria-modal="true" aria-label="Post">
    <button type="button" class="overlay-backdrop" aria-label="Close post" @click="close"></button>
    <article class="post-panel">
      <button type="button" class="close-button" aria-label="Close" @click="close">
        <i class="pi pi-times"></i>
      </button>

      <div v-if="isLoading" class="post-state">Loading post</div>
      <div v-else-if="!post" class="post-state">
        <strong>Post not found</strong>
        <button type="button" @click="close">Back to canvas</button>
      </div>
      <template v-else>
        <header class="post-header">
          <h1 v-if="post.title">{{ post.title }}</h1>
          <p>{{ post.text }}</p>
        </header>

        <div class="post-blocks">
          <section v-for="block in post.blocks" :key="block.id || `${block.type}-${blockText(block)}`" class="post-block" :class="`post-block--${block.type.toLowerCase()}`">
            <p v-if="block.type === 'TEXT'">{{ blockText(block) }}</p>
            <img v-else-if="block.type === 'IMAGE' && blockSource(block)" :src="blockSource(block)" alt="" />
            <video v-else-if="block.type === 'VIDEO' && blockSource(block)" :src="blockSource(block)" controls />
            <audio v-else-if="block.type === 'AUDIO' && blockSource(block)" :src="blockSource(block)" controls />
            <div v-else class="media-placeholder">
              <i :class="block.type === 'AUDIO' ? 'pi pi-volume-up' : block.type === 'VIDEO' ? 'pi pi-video' : 'pi pi-image'"></i>
              <span>{{ block.type.toLowerCase() }} block</span>
            </div>
          </section>
        </div>

        <footer class="post-tags">
          <span v-for="tag in post.tags" :key="tag">#{{ tag }}</span>
        </footer>

        <section class="comments-panel" aria-label="Comments">
          <form class="comment-form" @submit.prevent="sendComment()">
            <input v-model="commentText" type="text" placeholder="Add a comment" />
            <button type="submit"><i class="pi pi-send"></i></button>
          </form>

          <article v-for="comment in comments" :key="comment.id" class="comment-item">
            <p>{{ comment.text }}</p>
            <form class="reply-form" @submit.prevent="sendComment(comment.id)">
              <input v-model="replyDraft[comment.id]" type="text" placeholder="Reply" />
              <button type="submit">Reply</button>
            </form>
            <div v-if="comment.replies?.length" class="reply-list">
              <p v-for="reply in comment.replies" :key="reply.id">{{ reply.text }}</p>
            </div>
          </article>
        </section>
      </template>
    </article>
  </section>
</template>

<style scoped>
.post-overlay {
  position: fixed;
  z-index: 120;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 88px 18px 28px;
}

.overlay-backdrop {
  position: absolute;
  inset: 0;
  border: 0;
  background: rgba(248, 250, 252, 0.72);
  backdrop-filter: blur(10px);
  cursor: zoom-out;
}

.post-panel {
  position: relative;
  z-index: 1;
  width: min(880px, 100%);
  max-height: calc(100dvh - 116px);
  overflow: auto;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 14px;
  padding: 24px;
  background: #ffffff;
  box-shadow: 0 36px 90px rgba(15, 23, 42, 0.24);
}

.close-button {
  position: sticky;
  top: 0;
  float: right;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  cursor: pointer;
}

.post-header {
  display: grid;
  gap: 8px;
  padding-right: 46px;
}

.post-header span,
.post-tags span {
  color: #64748b;
  font-size: 12px;
  font-weight: 900;
  text-transform: uppercase;
}

.post-header h1 {
  margin: 0;
  color: #111827;
  font-size: clamp(28px, 4vw, 44px);
  line-height: 1.05;
  letter-spacing: 0;
}

.post-header p {
  margin: 0;
  color: #475569;
  font-size: 16px;
  font-weight: 600;
}

.post-blocks {
  display: grid;
  gap: 14px;
  margin-top: 22px;
}

.post-block {
  overflow: hidden;
  border-radius: 10px;
  background: #f8fafc;
}

.post-block p {
  margin: 0;
  padding: 18px;
  color: #111827;
  font-size: 18px;
  line-height: 1.5;
}

.post-block img,
.post-block video {
  width: 100%;
  max-height: 520px;
  display: block;
  object-fit: cover;
}

.post-block audio {
  width: calc(100% - 28px);
  margin: 14px;
}

.media-placeholder {
  min-height: 180px;
  display: grid;
  place-items: center;
  gap: 8px;
  color: #ffffff;
  background: linear-gradient(135deg, #111827, #334155);
  font-weight: 900;
}

.post-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 18px;
}

.comments-panel {
  display: grid;
  gap: 12px;
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid #e2e8f0;
}

.comment-form,
.reply-form {
  display: flex;
  gap: 8px;
}

.comment-form input,
.reply-form input {
  min-width: 0;
  flex: 1;
  border: 1px solid #e2e8f0;
  border-radius: 999px;
  padding: 10px 14px;
  font: inherit;
}

.comment-form button,
.reply-form button,
.post-state button {
  border: 0;
  border-radius: 999px;
  padding: 0 14px;
  background: #111827;
  color: #ffffff;
  font-weight: 900;
  cursor: pointer;
}

.comment-item {
  display: grid;
  gap: 8px;
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
}

.comment-item p {
  margin: 0;
}

.reply-list {
  display: grid;
  gap: 6px;
  margin-left: 18px;
  color: #475569;
}

.post-state {
  display: grid;
  justify-items: center;
  gap: 12px;
  padding: 70px 20px;
  color: #64748b;
  font-weight: 900;
}
</style>
