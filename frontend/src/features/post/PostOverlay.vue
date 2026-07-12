<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import type { AccountUser, CommentItem, ContentBlock, CurrentActor, FeedItem } from "@/api/types";
import ContentDocument from "@/features/contentDocument/ContentDocument.vue";
import ContentEditor from "@/features/contentDocument/ContentEditor.vue";
import {
  buildContentBlocks,
  filesFromAttachments,
  type ContentAttachment,
} from "@/features/contentDocument/contentModel";

const route = useRoute();
const router = useRouter();
const toast = useToast();
const currentActor = ref<CurrentActor | null>(null);
const post = ref<FeedItem["post"] | null>(null);
const comments = ref<CommentItem[]>([]);
const commentMarkdown = ref("");
const commentAttachments = ref<ContentAttachment[]>([]);
const isLoading = ref(true);
const isTogglingLike = ref(false);
const isSendingComment = ref(false);
const commentsOpen = ref(true);

const activeOwner = computed<AccountUser | null>(() => currentActor.value?.activeOwner ?? null);
const activeOwnerName = computed(() => activeOwner.value?.username || "User");
const activeOwnerInitial = computed(() => activeOwnerName.value.slice(0, 1).toUpperCase());

const postId = computed(() => String(route.params.postId || ""));
const allowComments = computed(() => post.value?.allowComments !== false);
const postBlocks = computed(() => post.value?.blocks || []);
const postMarkdown = computed(() => {
  const fromBlocks = ContentService.textFromBlocks(postBlocks.value);
  return fromBlocks || post.value?.text || "";
});
const author = computed(() => post.value?.author || null);
const authorName = computed(() => author.value?.username || post.value?.authorName || "User");
const authorInitial = computed(() => authorName.value.slice(0, 1).toUpperCase());
const sortedComments = computed(() => [...comments.value].sort((a, b) => Date.parse(a.createdAt || "") - Date.parse(b.createdAt || "")));
const highlightedCommentId = computed(() => String(route.query.comment || ""));

onMounted(async () => {
  await Promise.all([
    loadPost(),
    ContentService.currentActor().then((a) => (currentActor.value = a)).catch(() => undefined),
  ]);
  void ContentService.recordView(postId.value, 0).catch(() => undefined);
});

async function loadPost() {
  isLoading.value = true;
  try {
    post.value = await ContentService.post(postId.value);
    comments.value = await ContentService.comments(postId.value);
    await scrollToHighlightedComment();
  } finally {
    isLoading.value = false;
  }
}

watch(highlightedCommentId, () => {
  void scrollToHighlightedComment();
});

async function scrollToHighlightedComment() {
  const id = highlightedCommentId.value;
  if (!id) return;
  commentsOpen.value = true;
  await nextTick();
  document.getElementById(`comment-${id}`)?.scrollIntoView({ block: "center", behavior: "smooth" });
}

function close() {
  void router.push("/");
}

function relativeTime(value?: string | null): string {
  if (!value) return "";
  const diff = Date.now() - Date.parse(value);
  if (!Number.isFinite(diff)) return "";
  const minutes = Math.max(0, Math.floor(diff / 60000));
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

function commentAuthor(comment: CommentItem) {
  return comment.author || null;
}

function commentAuthorName(comment: CommentItem): string {
  return commentAuthor(comment)?.username || comment.authorName || "user";
}

function ownerPath(owner?: { ownerType?: string; username?: string | null } | null, fallbackName?: string | null) {
  const username = owner?.username || fallbackName || "user";
  return `/${owner?.ownerType === "ORGANIZATION" ? "o" : "u"}/${encodeURIComponent(username)}`;
}

function commentMarkdownValue(comment: CommentItem): string {
  return ContentService.textFromBlocks(comment.blocks || []) || comment.text || "";
}

function commentBlocks(comment: CommentItem): ContentBlock[] {
  return comment.blocks || [];
}

function mention(comment: CommentItem) {
  const next = `@${commentAuthorName(comment)} `;
  commentMarkdown.value = commentMarkdown.value.startsWith(next) ? commentMarkdown.value : `${next}${commentMarkdown.value}`;
  commentsOpen.value = true;
}

async function toggleLike() {
  if (!post.value || isTogglingLike.value) return;
  isTogglingLike.value = true;
  try {
    const next = post.value.likedByViewer
      ? await ContentService.unlikePost(post.value.id)
      : await ContentService.likePost(post.value.id);
    post.value = {
      ...post.value,
      likedByViewer: next.liked,
      likeCount: next.likeCount,
    };
  } catch (error) {
    toast.add({ severity: "error", summary: "Like", detail: error instanceof Error ? error.message : "Unable to update like", life: 5000 });
  } finally {
    isTogglingLike.value = false;
  }
}

async function toggleCommentLike(comment: CommentItem) {
  try {
    const next = comment.likedByViewer
      ? await ContentService.unlikeComment(comment.id)
      : await ContentService.likeComment(comment.id);
    comments.value = comments.value.map((item) => item.id === comment.id
      ? { ...item, likedByViewer: next.liked, likeCount: next.likeCount }
      : item);
  } catch (error) {
    toast.add({ severity: "error", summary: "Comment like", detail: error instanceof Error ? error.message : "Unable to update like", life: 5000 });
  }
}

async function sendComment() {
  if (!allowComments.value || isSendingComment.value) return;
  const text = commentMarkdown.value.trim();
  if (!text && commentAttachments.value.length === 0) return;
  isSendingComment.value = true;
  try {
    const blocks = buildContentBlocks(text, commentAttachments.value);
    const created = await ContentService.createCommentWithFiles(
      { postId: postId.value, text, blocks },
      filesFromAttachments(commentAttachments.value),
    );
    comments.value = [...comments.value, created];
    commentMarkdown.value = "";
    commentAttachments.value.forEach((attachment) => {
      if (attachment.url.startsWith("blob:")) URL.revokeObjectURL(attachment.url);
    });
    commentAttachments.value = [];
  } catch (error) {
    toast.add({ severity: "error", summary: "Comment", detail: error instanceof Error ? error.message : "Unable to comment", life: 5000 });
  } finally {
    isSendingComment.value = false;
  }
}
</script>

<template>
  <section class="post-page" aria-label="Post">
    <button type="button" class="close-button" aria-label="Close" @click="close">
      <i class="pi pi-times"></i>
    </button>

    <div v-if="isLoading" class="post-state">Loading post</div>
    <div v-else-if="!post" class="post-state">
      <strong>Post not found</strong>
      <button type="button" @click="close">Back to canvas</button>
    </div>

    <template v-else>
      <main class="post-document-shell">
        <article class="post-document">
          <ContentDocument :markdown="postMarkdown" :blocks="postBlocks" mode="post" />
        </article>
      </main>

      <aside class="comments-panel" :class="{ open: commentsOpen }" aria-label="Post details and comments">
        <header class="author-card">
          <RouterLink class="author-card__avatar" :to="ownerPath(author, authorName)">
            <img v-if="author?.avatarUrl" :src="author.avatarUrl" alt="" />
            <span v-else>{{ authorInitial }}</span>
          </RouterLink>
          <div>
            <RouterLink :to="ownerPath(author, authorName)">{{ author?.displayName || author?.firstName || authorName }}</RouterLink>
            <span>@{{ authorName }}</span>
          </div>
          <button type="button" aria-label="Toggle comments" @click="commentsOpen = !commentsOpen">
            <i class="pi pi-chevron-down"></i>
          </button>
        </header>

        <section class="post-actions" aria-label="Post reactions">
          <button type="button" :class="{ active: post.likedByViewer }" :disabled="isTogglingLike" @click="toggleLike">
            <i :class="post.likedByViewer ? 'pi pi-heart-fill' : 'pi pi-heart'"></i>
            <span>{{ post.likeCount || 0 }}</span>
          </button>
          <button type="button" @click="commentsOpen = !commentsOpen">
            <i class="pi pi-comments"></i>
            <span>{{ comments.length }}</span>
          </button>
        </section>

        <section class="comment-composer">
          <div class="comment-composer__identity">
            <span class="comment-composer__avatar">
              <img v-if="activeOwner?.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
              <i v-else-if="activeOwner?.ownerType === 'ORGANIZATION'" class="pi pi-building"></i>
              <span v-else>{{ activeOwnerInitial }}</span>
            </span>
            <span class="comment-composer__name">{{ activeOwner?.displayName || activeOwnerName }}</span>
          </div>
          <ContentEditor
            v-if="allowComments"
            v-model="commentMarkdown"
            :attachments="commentAttachments"
            compact
            placeholder="Comment with Markdown, mentions, and files."
            @update:attachments="commentAttachments = $event"
          />
          <p v-else class="comments-disabled">Comments are closed for this post.</p>
          <button v-if="allowComments" type="button" class="send-comment" :disabled="isSendingComment" @click="sendComment">
            <i class="pi pi-send"></i>
            <span>{{ isSendingComment ? "Sending" : "Send" }}</span>
          </button>
        </section>

        <section class="comment-list">
          <article
            v-for="comment in sortedComments"
            :id="`comment-${comment.id}`"
            :key="comment.id"
            class="comment-item"
            :class="{ highlighted: comment.id === highlightedCommentId }"
          >
            <RouterLink class="comment-item__avatar" :to="ownerPath(commentAuthor(comment), commentAuthorName(comment))">
              <img v-if="commentAuthor(comment)?.avatarUrl" :src="commentAuthor(comment)?.avatarUrl || ''" alt="" />
              <span v-else>{{ commentAuthorName(comment).slice(0, 1).toUpperCase() }}</span>
            </RouterLink>
            <div class="comment-item__body">
              <header>
                <RouterLink :to="ownerPath(commentAuthor(comment), commentAuthorName(comment))">{{ commentAuthor(comment)?.displayName || commentAuthor(comment)?.firstName || commentAuthorName(comment) }}</RouterLink>
                <span>@{{ commentAuthorName(comment) }}</span>
                <time>{{ relativeTime(comment.createdAt) }}</time>
                <button type="button" class="comment-menu" title="Comment menu">
                  <i class="pi pi-ellipsis-v"></i>
                </button>
              </header>
              <ContentDocument :markdown="commentMarkdownValue(comment)" :blocks="commentBlocks(comment)" mode="comment" />
              <footer>
                <button type="button" :class="{ active: comment.likedByViewer }" @click="toggleCommentLike(comment)">
                  <i :class="comment.likedByViewer ? 'pi pi-heart-fill' : 'pi pi-heart'"></i>
                  <span>{{ comment.likeCount || 0 }}</span>
                </button>
                <button type="button" title="Mention" @click="mention(comment)">
                  <i class="pi pi-arrow-right"></i>
                </button>
              </footer>
            </div>
          </article>
        </section>
      </aside>
    </template>
  </section>
</template>

<style scoped>
.post-page {
  position: fixed;
  z-index: 120;
  inset: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(340px, 420px);
  background: #f8fafc;
  overflow: hidden;
}

.close-button {
  position: fixed;
  z-index: 6;
  top: 22px;
  left: 22px;
  width: 42px;
  height: 42px;
  border: 0;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  cursor: pointer;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.22);
}

.post-document-shell {
  min-width: 0;
  height: 100dvh;
  overflow: auto;
  padding: 82px clamp(20px, 7vw, 112px) 60px;
}

.post-document {
  width: min(860px, 100%);
  margin: 0 auto;
  padding: clamp(26px, 5vw, 58px);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.98);
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.08);
}

.comments-panel {
  height: 100dvh;
  display: grid;
  grid-template-rows: auto auto auto minmax(0, 1fr);
  gap: 14px;
  padding: 20px 18px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: -20px 0 52px rgba(15, 23, 42, 0.1);
  overflow: hidden;
}

.author-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
}

.author-card__avatar,
.comment-item__avatar {
  width: 44px;
  height: 44px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #111827;
  color: #ffffff;
  text-decoration: none;
  font-weight: 900;
}

.author-card__avatar img,
.comment-item__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.author-card a,
.comment-item header a {
  color: #111827;
  text-decoration: none;
  font-weight: 900;
}

.author-card span,
.comment-item header span,
.comment-item time {
  display: block;
  min-width: 0;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
  font-weight: 800;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.author-card button {
  display: none;
}

.post-actions {
  display: flex;
  gap: 6px;
}

.post-actions button,
.send-comment,
.comment-item footer button,
.comment-menu,
.post-state button {
  border: 0;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font: inherit;
  font-weight: 900;
  cursor: pointer;
}

.post-actions button {
  height: 36px;
  min-width: 50px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0 12px;
}

.post-actions button.active,
.comment-item footer button.active {
  color: #fb7185;
}

.comment-composer {
  display: grid;
  gap: 10px;
  border-top: 1px solid rgba(15, 23, 42, 0.08);
  padding-top: 14px;
}

.comment-composer__identity {
  display: flex;
  align-items: center;
  gap: 8px;
}

.comment-composer__avatar {
  width: 26px;
  height: 26px;
  border-radius: 999px;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: #111827;
  color: #ffffff;
  font-size: 11px;
  font-weight: 900;
  flex-shrink: 0;
}

.comment-composer__avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.comment-composer__name {
  font-size: 12px;
  font-weight: 900;
  color: #475569;
}

.send-comment {
  height: 38px;
  justify-self: end;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 14px;
}

.send-comment:disabled {
  opacity: 0.6;
  cursor: wait;
}

.comments-disabled {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  font-weight: 800;
}

.comment-list {
  min-height: 0;
  display: grid;
  align-content: start;
  gap: 16px;
  overflow: auto;
  padding-right: 4px;
}

.comment-item {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 10px;
  border-radius: 8px;
  padding: 4px;
}

.comment-item.highlighted {
  background: rgba(250, 204, 21, 0.18);
}

.comment-item__avatar {
  width: 34px;
  height: 34px;
  font-size: 13px;
}

.comment-item__body {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.comment-item header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 6px;
}

.comment-menu {
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  background: transparent;
  color: #64748b;
}

.comment-item footer {
  display: flex;
  gap: 4px;
}

.comment-item footer button {
  min-width: 30px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  background: #f1f5f9;
  color: #475569;
  font-size: 12px;
}

.post-state {
  grid-column: 1 / -1;
  min-height: 100dvh;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 12px;
  color: #64748b;
  font-weight: 900;
}

.post-state button {
  padding: 10px 14px;
}

@media (max-width: 880px) {
  .post-page {
    display: block;
  }

  .post-document-shell {
    height: 100dvh;
    padding: 78px 14px 154px;
  }

  .post-document {
    padding: 24px 18px;
  }

  .comments-panel {
    position: fixed;
    z-index: 5;
    left: 0;
    right: 0;
    bottom: 0;
    height: min(78dvh, 620px);
    border-radius: 18px 18px 0 0;
    transform: translateY(calc(100% - 72px));
    transition: transform 180ms ease;
    box-shadow: 0 -20px 52px rgba(15, 23, 42, 0.16);
  }

  .comments-panel.open {
    transform: translateY(0);
  }

  .author-card button {
    width: 34px;
    height: 34px;
    border: 0;
    border-radius: 999px;
    display: grid;
    place-items: center;
    background: #111827;
    color: #ffffff;
  }
}
</style>
