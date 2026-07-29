<script setup lang="ts">
import OnixIcon from "@/shared/ui/OnixIcon.vue";
import { onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ContentService } from "@/shared/api/contentService";
import { withEmbedQuery } from "@/features/embed/lib/profileEmbed";

const router = useRouter();
const route = useRoute();

onMounted(async () => {
  const actor = await ContentService.currentActor();
  const owner = actor.activeOwner;
  const prefix = owner.ownerType === "ORGANIZATION" ? "o" : "u";
  await router.replace(withEmbedQuery(route, `/${prefix}/${encodeURIComponent(owner.username)}`));
});
</script>

<template>
  <main class="profile-home-redirect">
    <OnixIcon name="refresh" class="onix-icon--spin" :size="24" />
  </main>
</template>

<style scoped>
.profile-home-redirect {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  color: var(--onix-color-text-muted);
}
</style>
