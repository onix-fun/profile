<script setup lang="ts">
import { computed, ref } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import { displayUsername, postSnippet } from "@/features/display/displayText";
import type { AccountSearchUser, FeedItem } from "@/api/types";

type SearchTab = "all" | "users" | "posts" | "comments" | "tags";
type SortMode = "relevance" | "new" | "popular";

const router = useRouter();
const toast = useToast();
const query = ref("");
const activeTab = ref<SearchTab>("all");
const sortMode = ref<SortMode>("relevance");
const dateFilter = ref("any");
const authorFilter = ref("");
const tagFilter = ref("");
const owners = ref<AccountSearchUser[]>([]);
const posts = ref<FeedItem[]>([]);
const isLoading = ref(false);

const tabs: Array<{ id: SearchTab; label: string }> = [
  { id: "all", label: "All" },
  { id: "users", label: "Users" },
  { id: "posts", label: "Posts" },
  { id: "comments", label: "Comments" },
  { id: "tags", label: "Tags" },
];

const tagQueries = computed(() => {
  const fromQuery = query.value.split(/\s+/).map((tag) => tag.replace(/^#/, "").trim().toLowerCase()).filter(Boolean);
  const fromFilter = tagFilter.value.split(/[,\s]+/).map((tag) => tag.replace(/^#/, "").trim().toLowerCase()).filter(Boolean);
  return Array.from(new Set([...fromQuery, ...fromFilter])).slice(0, 20);
});

const filteredPosts = computed(() => {
  const author = authorFilter.value.trim().replace(/^@/, "").toLowerCase();
  const now = Date.now();
  const maxAgeMs = dateFilter.value === "day"
    ? 24 * 60 * 60 * 1000
    : dateFilter.value === "week"
      ? 7 * 24 * 60 * 60 * 1000
      : dateFilter.value === "month"
        ? 30 * 24 * 60 * 60 * 1000
        : null;

  const filtered = posts.value.filter((item) => {
    const authorText = [item.post.author?.username, item.post.author?.displayName, item.post.authorName, item.post.authorId].filter(Boolean).join(" ").toLowerCase();
    if (author && !authorText.includes(author)) return false;
    if (maxAgeMs && item.post.createdAt && now - Date.parse(item.post.createdAt) > maxAgeMs) return false;
    return true;
  });

  return [...filtered].sort((a, b) => {
    if (sortMode.value === "new") return Date.parse(b.post.createdAt || "") - Date.parse(a.post.createdAt || "");
    if (sortMode.value === "popular") return (b.post.likeCount || 0) - (a.post.likeCount || 0);
    return b.score - a.score;
  });
});

const tagResults = computed(() => Array.from(new Set(filteredPosts.value.flatMap((item) => item.post.tags))).sort());

async function search() {
  const text = query.value.trim();
  if (!text && !tagFilter.value.trim()) return;
  isLoading.value = true;
  try {
    const [nextOwners, nextPosts] = await Promise.all([
      text.length >= 2 ? ProfileService.searchOwners(text, 12) : Promise.resolve([]),
      ContentService.feed(tagQueries.value, 32),
    ]);
    owners.value = nextOwners;
    posts.value = nextPosts;
  } catch (error) {
    toast.add({ severity: "error", summary: "Search", detail: error instanceof Error ? error.message : "Unable to search", life: 5000 });
  } finally {
    isLoading.value = false;
  }
}

function openOwner(owner: AccountSearchUser) {
  const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
  void router.push(`/${prefix}/${encodeURIComponent(owner.username)}`);
}

function openPost(item: FeedItem) {
  void router.push(`/p/${encodeURIComponent(item.post.id)}`);
}

function userName(user: AccountSearchUser): string {
  return displayUsername(user.username);
}

function fullName(user: AccountSearchUser): string {
  return user.displayName || [user.firstName, user.lastName].filter(Boolean).join(" ");
}
</script>

<template>
  <main class="search-shell">
    <section class="search-panel">
      <div class="search-box">
        <i class="pi pi-search"></i>
        <input v-model="query" type="search" placeholder="Search users, posts, tags" @keyup.enter="search" />
        <button type="button" @click="search">Search</button>
      </div>

      <div class="search-tabs" role="tablist" aria-label="Search filters">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          type="button"
          :class="{ active: activeTab === tab.id }"
          @click="activeTab = tab.id"
        >
          {{ tab.label }}
        </button>
      </div>

      <section class="search-filters" aria-label="Search filters">
        <label>
          <span>Date</span>
          <select v-model="dateFilter">
            <option value="any">Any time</option>
            <option value="day">Today</option>
            <option value="week">This week</option>
            <option value="month">This month</option>
          </select>
        </label>
        <label>
          <span>Author</span>
          <input v-model="authorFilter" type="text" placeholder="@username" />
        </label>
        <label>
          <span>Tags</span>
          <input v-model="tagFilter" type="text" placeholder="#design #music" @keyup.enter="search" />
        </label>
        <label>
          <span>Sort</span>
          <select v-model="sortMode">
            <option value="relevance">Relevance</option>
            <option value="new">Newest</option>
            <option value="popular">Popular</option>
          </select>
        </label>
      </section>

      <div v-if="isLoading" class="search-state">Searching</div>

      <section v-if="!isLoading && (activeTab === 'all' || activeTab === 'users')" class="result-section">
        <header>
          <strong>Accounts</strong>
          <span>{{ owners.length }}</span>
        </header>
        <button v-for="user in owners" :key="`${user.ownerType || 'USER'}:${user.id}`" type="button" class="user-result" @click="openOwner(user)">
          <span class="user-avatar">
            <img v-if="user.avatarUrl" :src="user.avatarUrl" alt="" />
            <i v-else :class="user.ownerType === 'ORGANIZATION' ? 'pi pi-building' : 'pi pi-user'"></i>
          </span>
          <span>
            <strong>{{ userName(user) }}</strong>
            <small v-if="fullName(user)">{{ fullName(user) }}</small>
          </span>
          <small v-if="user.ownerType === 'ORGANIZATION'" class="owner-badge">Organization</small>
        </button>
      </section>

      <section v-if="!isLoading && (activeTab === 'all' || activeTab === 'posts')" class="result-section">
        <header>
          <strong>Posts</strong>
          <span>{{ filteredPosts.length }}</span>
        </header>
        <article v-for="item in filteredPosts" :key="item.post.id" class="result-row" @click="openPost(item)">
          <strong>{{ postSnippet(item.post) }}</strong>
          <p>{{ postSnippet({ ...item.post, title: undefined }) }}</p>
          <footer>
            <span v-for="tag in item.post.tags" :key="tag">#{{ tag }}</span>
            <small><i class="pi pi-heart"></i>{{ item.post.likeCount || 0 }}</small>
          </footer>
        </article>
      </section>

      <section v-if="!isLoading && activeTab === 'comments'" class="search-state">
        Comment search will use indexed comment events when search-service query endpoints are wired.
      </section>

      <section v-if="!isLoading && (activeTab === 'all' || activeTab === 'tags')" class="result-section result-section--tags">
        <header>
          <strong>Tags</strong>
          <span>{{ tagResults.length }}</span>
        </header>
        <button v-for="tag in tagResults" :key="tag" type="button" @click="query = `#${tag}`; search()">
          #{{ tag }}
        </button>
      </section>
    </section>
  </main>
</template>

<style scoped>
.search-shell {
  min-height: 100dvh;
  background: var(--bg);
  color: var(--text);
}

.search-panel {
  width: min(940px, calc(100% - 32px));
  margin: 0 auto;
  padding: 102px 0 40px;
  display: grid;
  gap: 14px;
}

.search-box,
.search-filters,
.result-section {
  border: 1px solid var(--surface-active);
  border-radius: 10px;
  background: var(--surface-raised);
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.08);
}

.search-box {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  padding: 10px;
}

.search-box input,
.search-filters input,
.search-filters select {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
}

.search-box button,
.search-tabs button,
.result-section--tags button {
  border: 0;
  border-radius: 8px;
  padding: 10px 14px;
  background: var(--btn-primary-bg);
  color: var(--btn-primary-text);
  font-weight: 900;
  cursor: pointer;
}

.search-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.search-tabs button {
  background: #e2e8f0;
  color: #111827;
}

.search-tabs button.active {
  background: #111827;
  color: #ffffff;
}

.search-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding: 10px;
}

.search-filters label {
  display: grid;
  gap: 4px;
  border-radius: 8px;
  padding: 8px 10px;
  background: var(--surface-muted);
}

.search-filters span,
.result-section header span,
.search-state {
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
  text-transform: uppercase;
}

.result-section {
  display: grid;
  gap: 8px;
  padding: 12px;
}

.result-section header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 2px 2px 6px;
}

.user-result,
.result-row {
  border: 1px solid rgba(15, 23, 42, 0.07);
  border-radius: 8px;
  background: #ffffff;
  cursor: pointer;
}

.user-result {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
  color: inherit;
  text-align: left;
}

.user-avatar {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: linear-gradient(135deg, #111827, #0f766e);
  color: #ffffff;
  overflow: hidden;
}

.user-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-result strong,
.user-result small {
  display: block;
}

.user-result small {
  color: var(--muted);
  font-weight: 700;
}

.owner-badge {
  margin-left: auto;
  padding: 4px 8px;
  border-radius: 999px;
  background: #ecfeff;
  color: #0f766e !important;
  font-size: 11px;
  white-space: nowrap;
}

.result-row {
  display: grid;
  gap: 8px;
  padding: 14px;
}

.result-row p {
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--muted);
  font-weight: 650;
}

.result-row footer {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 800;
}

.result-row footer small {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font: inherit;
}

.result-section--tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.result-section--tags header {
  width: 100%;
}

.result-section--tags button {
  background: #ecfdf5;
  color: #047857;
}

.search-state {
  padding: 16px;
}

@media (max-width: 760px) {
  .search-panel {
    padding-top: 92px;
  }

  .search-filters {
    grid-template-columns: 1fr;
  }
}
</style>
