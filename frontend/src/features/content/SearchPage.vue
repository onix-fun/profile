<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useToast } from "primevue/usetoast";
import { ContentService } from "@/api/contentService";
import { ProfileService } from "@/api/profileService";
import { displayUsername, postSnippet } from "@/features/display/displayText";
import type { FeedItem, SearchItem, SearchSuggestion } from "@/api/types";

type SearchTypeFilter = "posts" | "collections" | "comments" | "tags";
type SortMode = "relevance" | "new" | "popular";

interface SearchHistoryItem {
  query: string;
  types: SearchTypeFilter[];
  sort: SortMode;
  searchedAt: string;
}

const SEARCH_HISTORY_KEY = "onix.search.history";

const router = useRouter();
const toast = useToast();
const query = ref("");
const selectedTypes = ref<SearchTypeFilter[]>([]);
const sortMode = ref<SortMode>("relevance");
const results = ref<SearchItem[]>([]);
const suggestions = ref<SearchSuggestion[]>([]);
const history = ref<SearchHistoryItem[]>([]);
const discoverItems = ref<FeedItem[]>([]);
const isSearchFocused = ref(false);
const isLoading = ref(false);
const isSuggesting = ref(false);
let suggestTimer: number | undefined;

const typeFilters: Array<{ id: SearchTypeFilter; label: string; icon: string }> = [
  { id: "posts", label: "Posts", icon: "pi pi-file" },
  { id: "collections", label: "Collections", icon: "pi pi-folder" },
  { id: "comments", label: "Comments", icon: "pi pi-comments" },
  { id: "tags", label: "Tags", icon: "pi pi-hashtag" },
];

const sortFilters: Array<{ id: SortMode; label: string; icon: string }> = [
  { id: "relevance", label: "Best", icon: "pi pi-sparkles" },
  { id: "new", label: "Newest", icon: "pi pi-clock" },
  { id: "popular", label: "Popular", icon: "pi pi-heart" },
];

const visibleHistory = computed(() => {
  const needle = query.value.trim().toLowerCase();
  return history.value
    .filter((item) => !needle || item.query.toLowerCase().includes(needle))
    .slice(0, 6);
});

const showDiscover = computed(() => !isSearchFocused.value && !query.value.trim() && results.value.length === 0);
const showHistory = computed(() => isSearchFocused.value && !query.value.trim() && visibleHistory.value.length > 0);
const showSuggestions = computed(() => isSearchFocused.value && query.value.trim().length > 0 && suggestions.value.length > 0);

onMounted(() => {
  history.value = loadHistory();
  void loadDiscover();
});

watch(query, () => {
  window.clearTimeout(suggestTimer);
  const text = query.value.trim();
  if (!text) {
    suggestions.value = [];
    return;
  }
  suggestTimer = window.setTimeout(() => void loadSuggestions(text), 180);
});

async function loadDiscover() {
  try {
    const seed = `search-${new Date().toISOString().slice(0, 10)}`;
    const feed = await ContentService.recommendationFeed({ chunkX: 0, chunkY: 0, sessionSeed: seed, limit: 12 });
    discoverItems.value = feed.items;
  } catch {
    discoverItems.value = [];
  }
}

async function loadSuggestions(text: string) {
  isSuggesting.value = true;
  try {
    const response = await ProfileService.searchSuggest(text, 8);
    const local = visibleHistory.value.map((item) => ({
      type: "RECENT" as const,
      value: item.query,
      label: item.query,
    }));
    suggestions.value = [...local, ...response.suggestions]
      .filter((item) => item.value)
      .filter((item, index, all) => all.findIndex((candidate) => `${candidate.type}:${candidate.value}` === `${item.type}:${item.value}`) === index)
      .slice(0, 8);
  } catch {
    suggestions.value = [];
  } finally {
    isSuggesting.value = false;
  }
}

async function search() {
  const text = query.value.trim();
  if (!text) return;
  isLoading.value = true;
  try {
    const response = await ProfileService.search({
      q: text,
      types: selectedTypes.value,
      sort: sortMode.value,
      limit: 40,
    });
    results.value = response.items;
    saveHistory({ query: text, types: selectedTypes.value, sort: sortMode.value, searchedAt: new Date().toISOString() });
  } catch (error) {
    toast.add({ severity: "error", summary: "Search", detail: error instanceof Error ? error.message : "Unable to search", life: 5000 });
  } finally {
    isLoading.value = false;
  }
}

function toggleType(type: SearchTypeFilter) {
  selectedTypes.value = selectedTypes.value.includes(type)
    ? selectedTypes.value.filter((item) => item !== type)
    : [...selectedTypes.value, type];
  if (query.value.trim()) void search();
}

function setSort(sort: SortMode) {
  sortMode.value = sort;
  if (query.value.trim()) void search();
}

function clearTypes() {
  selectedTypes.value = [];
  if (query.value.trim()) void search();
}

function applyHistory(item: SearchHistoryItem) {
  query.value = item.query;
  selectedTypes.value = item.types;
  sortMode.value = item.sort;
  void search();
}

function applySuggestion(suggestion: SearchSuggestion) {
  if (suggestion.owner) {
    const prefix = suggestion.owner.ownerType === "ORGANIZATION" ? "o" : "u";
    void router.push(`/${prefix}/${encodeURIComponent(suggestion.owner.username)}`);
    return;
  }
  query.value = suggestion.type === "TAG" && !suggestion.value.startsWith("#") ? `#${suggestion.value}` : suggestion.value;
  void search();
}

function openResult(item: SearchItem) {
  void router.push(item.url);
}

function openDiscover(item: FeedItem) {
  void router.push(`/p/${encodeURIComponent(item.post.id)}`);
}

function iconFor(item: SearchItem): string {
  if (item.type === "COLLECTION") return "pi pi-folder";
  if (item.type === "COMMENT") return "pi pi-comments";
  if (item.type === "TAG") return "pi pi-hashtag";
  return "pi pi-file";
}

function ownerLabel(item: SearchItem): string {
  return item.owner?.displayName || item.owner?.username || item.meta.ownerId || "";
}

function loadHistory(): SearchHistoryItem[] {
  try {
    const parsed = JSON.parse(localStorage.getItem(SEARCH_HISTORY_KEY) || "[]");
    return Array.isArray(parsed) ? parsed.slice(0, 10) : [];
  } catch {
    return [];
  }
}

function saveHistory(item: SearchHistoryItem) {
  history.value = [item, ...history.value.filter((existing) => existing.query.toLowerCase() !== item.query.toLowerCase())].slice(0, 10);
  localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify(history.value));
}
</script>

<template>
  <main class="search-shell">
    <section class="search-panel">
      <div class="search-box" :class="{ focused: isSearchFocused }">
        <i class="pi pi-search"></i>
        <input
          v-model="query"
          type="search"
          placeholder="Search posts, collections, comments, tags"
          @focus="isSearchFocused = true"
          @keydown.enter.prevent="search"
        />
        <button type="button" :disabled="isLoading || !query.trim()" aria-label="Search" @click="search">
          <i class="pi pi-arrow-right"></i>
        </button>
      </div>

      <div class="filter-row" aria-label="Search filters">
        <button type="button" class="chip" :class="{ active: selectedTypes.length === 0 }" @click="clearTypes">
          <i class="pi pi-th-large"></i>
          <span>All</span>
        </button>
        <button
          v-for="filter in typeFilters"
          :key="filter.id"
          type="button"
          class="chip"
          :class="{ active: selectedTypes.includes(filter.id) }"
          @click="toggleType(filter.id)"
        >
          <i :class="filter.icon"></i>
          <span>{{ filter.label }}</span>
        </button>
        <span class="filter-divider"></span>
        <button
          v-for="sort in sortFilters"
          :key="sort.id"
          type="button"
          class="chip"
          :class="{ active: sortMode === sort.id }"
          @click="setSort(sort.id)"
        >
          <i :class="sort.icon"></i>
          <span>{{ sort.label }}</span>
        </button>
      </div>

      <section v-if="showHistory" class="suggest-panel" aria-label="Recent searches">
        <button v-for="item in visibleHistory" :key="item.searchedAt" type="button" @click="applyHistory(item)">
          <i class="pi pi-history"></i>
          <span>{{ item.query }}</span>
        </button>
      </section>

      <section v-else-if="showSuggestions" class="suggest-panel" aria-label="Search suggestions">
        <button v-for="suggestion in suggestions" :key="`${suggestion.type}:${suggestion.value}`" type="button" @click="applySuggestion(suggestion)">
          <i :class="suggestion.owner ? 'pi pi-user' : suggestion.type === 'TAG' ? 'pi pi-hashtag' : 'pi pi-search'"></i>
          <span>{{ suggestion.label }}</span>
        </button>
      </section>

      <div v-if="isLoading || isSuggesting" class="search-state">{{ isLoading ? "Searching" : "Loading suggestions" }}</div>

      <section v-if="showDiscover" class="discover-grid" aria-label="Suggested content">
        <article v-for="item in discoverItems" :key="item.post.id" class="discover-item" @click="openDiscover(item)">
          <strong>{{ postSnippet(item.post) }}</strong>
          <p>{{ item.post.author?.displayName || displayUsername(item.post.author?.username || item.post.authorName || "user") }}</p>
          <footer>
            <span v-for="tag in item.post.tags.slice(0, 3)" :key="tag">#{{ tag }}</span>
          </footer>
        </article>
      </section>

      <section v-if="!isLoading && results.length > 0" class="result-list" aria-label="Search results">
        <article v-for="item in results" :key="`${item.type}:${item.id}`" class="result-row" @click="openResult(item)">
          <span class="result-icon">
            <i :class="iconFor(item)"></i>
          </span>
          <span class="result-copy">
            <strong>{{ item.title || item.snippet || item.id }}</strong>
            <small v-if="ownerLabel(item)">{{ ownerLabel(item) }}</small>
            <p v-if="item.snippet">{{ item.snippet }}</p>
            <footer>
              <span v-for="tag in item.tags.slice(0, 4)" :key="tag">#{{ tag }}</span>
              <span v-if="item.type === 'COLLECTION'">{{ item.meta.itemCount || 0 }} posts</span>
            </footer>
          </span>
        </article>
      </section>

      <section v-else-if="!isLoading && query.trim() && results.length === 0" class="search-state">
        No results
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
  width: min(980px, calc(100% - 28px));
  margin: 0 auto;
  padding: 96px 0 40px;
  display: grid;
  gap: 12px;
}

.search-box {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) 42px;
  align-items: center;
  gap: 10px;
  min-height: 58px;
  padding: 8px 10px 8px 16px;
  border: 1px solid var(--surface-active);
  border-radius: 8px;
  background: var(--surface-raised);
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.08);
}

.search-box.focused {
  border-color: #111827;
}

.search-box input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--text);
  font: inherit;
}

.search-box button,
.chip,
.suggest-panel button {
  border: 0;
  cursor: pointer;
}

.search-box button {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  background: #111827;
  color: #fff;
}

.search-box button:disabled {
  opacity: 0.45;
  cursor: default;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 999px;
  background: #e5e7eb;
  color: #111827;
  font-weight: 800;
}

.chip.active {
  background: #111827;
  color: #ffffff;
}

.filter-divider {
  width: 1px;
  height: 26px;
  background: var(--surface-active);
}

.suggest-panel,
.result-list {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid var(--surface-active);
  border-radius: 8px;
  background: var(--surface-raised);
}

.suggest-panel button {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 42px;
  padding: 0 10px;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  text-align: left;
}

.suggest-panel button:hover,
.result-row:hover,
.discover-item:hover {
  background: var(--surface-muted);
}

.discover-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.discover-item,
.result-row {
  border: 1px solid var(--surface-active);
  border-radius: 8px;
  background: var(--surface-raised);
  cursor: pointer;
}

.discover-item {
  min-height: 136px;
  padding: 14px;
  display: grid;
  align-content: space-between;
  gap: 10px;
}

.discover-item strong,
.result-copy strong {
  line-height: 1.25;
}

.discover-item p,
.result-copy p,
.result-copy small,
.search-state {
  color: var(--muted);
}

.discover-item footer,
.result-copy footer {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 12px;
  font-weight: 800;
  color: var(--muted);
}

.result-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 10px;
  padding: 12px;
}

.result-icon {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #f3f4f6;
  color: #111827;
}

.result-copy {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.result-copy strong,
.result-copy p,
.result-copy small {
  overflow-wrap: anywhere;
}

.result-copy p {
  margin: 0;
}

.search-state {
  padding: 20px;
  text-align: center;
  font-size: 12px;
  font-weight: 900;
  text-transform: uppercase;
}

@media (max-width: 760px) {
  .search-panel {
    width: min(100% - 18px, 980px);
    padding-top: 82px;
  }

  .discover-grid {
    grid-template-columns: 1fr;
  }

  .filter-divider {
    display: none;
  }
}
</style>
