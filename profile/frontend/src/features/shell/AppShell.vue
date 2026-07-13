<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { ContentService } from "@/api/contentService";
import type { AccountUser, CurrentActor } from "@/api/types";
import { runtimeConfig } from "@/runtime-config";
import { accountSettingsUrl } from "@/features/profile/accountLinks";
import { contentUrl } from "@/api/navigation";

const route = useRoute();
const actor = ref<CurrentActor | null>(null);
const menuOpen = ref(false);

const activeOwner = computed<AccountUser | null>(() => actor.value?.activeOwner || null);
const isOrg = computed(() => activeOwner.value?.ownerType === "ORGANIZATION");
const initials = computed(() => {
  const source = activeOwner.value?.displayName || activeOwner.value?.username || "U";
  return source.slice(0, 1).toUpperCase();
});
const ownerName = computed(() => activeOwner.value?.displayName || activeOwner.value?.username || "Account");
const profilePath = computed(() => {
  const owner = activeOwner.value;
  if (!owner) return "/u/me";
  const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
  return `/${prefix}/${encodeURIComponent(owner.username)}`;
});
const controlsHidden = computed(() => (
  false
));
const headerVisible = computed(() => (
  !controlsHidden.value
  && route.path !== "/"
  && route.name !== "Profile"
  && route.name !== "SocialCanvas"
));
const settingsHref = computed(() => accountSettingsUrl(runtimeConfig.accountFrontendUrl, window.location.href));

onMounted(async () => {
  try {
    actor.value = await ContentService.currentActor();
  } catch {
    actor.value = null;
  }
});

function closeMenu() {
  menuOpen.value = false;
}

function openCreatePost() {
  closeMenu();
  window.location.assign(contentUrl("/post/new", true));
}

function openCreateStory() {
  closeMenu();
  window.location.assign(contentUrl("/story/new", true));
}
</script>

<template>
  <div class="app-shell" @keydown.esc="closeMenu">
    <header v-if="headerVisible" class="app-header" aria-label="Main navigation">
      <a class="brand-mark" :href="contentUrl('/')" aria-label="Open feed">
        <span class="brand-word">Onix</span>
      </a>
    </header>

    <button
      v-if="!controlsHidden"
      class="avatar-menu-button"
      :class="{ 'avatar-menu-button--org': isOrg }"
      type="button"
      aria-label="Open menu"
      :aria-expanded="menuOpen"
      @click="menuOpen = !menuOpen"
    >
      <img v-if="activeOwner?.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
      <i v-else-if="isOrg" class="pi pi-building"></i>
      <span v-else>{{ initials }}</span>
      <i class="pi pi-bars"></i>
    </button>

    <Transition name="menu-fade">
      <aside v-if="menuOpen && !controlsHidden" class="account-menu" aria-label="Account menu">
        <div class="account-menu__identity">
          <div class="account-menu__avatar">
            <img v-if="activeOwner?.avatarUrl" :src="activeOwner.avatarUrl" alt="" />
            <i v-else-if="isOrg" class="pi pi-building"></i>
            <span v-else>{{ initials }}</span>
          </div>
          <div>
            <strong>{{ ownerName }}</strong>
            <span>@{{ activeOwner?.username }}</span>
          </div>
        </div>

        <nav class="account-menu__nav">
          <a :href="contentUrl('/')" @click="closeMenu"><i class="pi pi-home"></i>Feed</a>
          <RouterLink :to="profilePath" @click="closeMenu"><i :class="isOrg ? 'pi pi-building' : 'pi pi-user'"></i>Profile</RouterLink>
          <RouterLink to="/search" @click="closeMenu"><i class="pi pi-search"></i>Search</RouterLink>
          <button type="button" @click="openCreatePost"><i class="pi pi-plus-circle"></i>Create post</button>
          <button type="button" @click="openCreateStory"><i class="pi pi-stopwatch"></i>Create story</button>
          <a :href="settingsHref" @click="closeMenu"><i class="pi pi-cog"></i>Settings</a>
        </nav>
      </aside>
    </Transition>

    <main class="app-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100dvh;
  background:
    radial-gradient(circle at 18% 22%, rgba(59, 130, 246, 0.08), transparent 28%),
    linear-gradient(180deg, #ffffff 0%, #f7f9fb 100%);
}

.app-header {
  position: fixed;
  z-index: 80;
  top: 0;
  left: 0;
  right: 0;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 18px clamp(18px, 3vw, 42px);
  pointer-events: none;
}

.brand-mark,
.avatar-menu-button {
  pointer-events: auto;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #111827;
  text-decoration: none;
  font-weight: 900;
  letter-spacing: 0;
}

.brand-word {
  font-size: 20px;
}

.avatar-menu-button {
  position: fixed;
  z-index: 86;
  right: clamp(18px, 3vw, 42px);
  bottom: max(22px, env(safe-area-inset-bottom));
  width: 48px;
  height: 48px;
  border: 1px solid rgba(17, 24, 39, 0.1);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.76);
  color: #111827;
  box-shadow: 0 14px 34px rgba(15, 23, 42, 0.13);
  cursor: pointer;
  overflow: visible;
}

.avatar-menu-button img,
.avatar-menu-button > i,
.avatar-menu-button > span {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  border-radius: inherit;
  object-fit: cover;
  font-weight: 900;
}

.avatar-menu-button--org {
  border-color: #818cf8;
  box-shadow: 0 14px 34px rgba(129, 140, 248, 0.2);
}

.avatar-menu-button i {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-size: 10px;
}

.account-menu {
  position: fixed;
  z-index: 90;
  right: clamp(14px, 3vw, 34px);
  bottom: calc(max(22px, env(safe-area-inset-bottom)) + 64px);
  width: min(320px, calc(100vw - 28px));
  padding: 12px;
  border: 1px solid rgba(15, 23, 42, 0.1);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.92);
  box-shadow: 0 26px 70px rgba(15, 23, 42, 0.18);
  backdrop-filter: blur(18px);
}

.account-menu__identity {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 8px 12px;
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
}

.account-menu__avatar {
  width: 44px;
  height: 44px;
  display: grid;
  place-items: center;
  border-radius: 999px;
  background: #111827;
  color: #ffffff;
  font-weight: 900;
  overflow: hidden;
}

.account-menu__avatar img,
.account-menu__avatar i {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  object-fit: cover;
}

.account-menu__identity strong,
.account-menu__identity span {
  display: block;
}

.account-menu__identity span {
  color: #667085;
  font-size: 13px;
  font-weight: 700;
}

.account-menu__nav {
  display: grid;
  gap: 4px;
  padding-top: 10px;
}

.account-menu__nav a,
.account-menu__nav button {
  width: 100%;
  height: 42px;
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 8px;
  padding: 0 10px;
  background: transparent;
  color: #111827;
  text-decoration: none;
  font: inherit;
  font-weight: 800;
  cursor: pointer;
}

.account-menu__nav a:hover,
.account-menu__nav button:hover {
  background: #f1f5f9;
}

.app-main {
  min-height: 100dvh;
}

.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 140ms ease, transform 140ms ease;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
