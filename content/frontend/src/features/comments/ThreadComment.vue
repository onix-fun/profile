<script setup lang="ts">
import { computed } from "vue";
import { ChevronDown, EyeOff, Heart, Link2, MoreHorizontal, Pin, Reply, Trash2 } from "lucide-vue-next";
import type { CommentItem } from "@/api/types";
import CommentDocumentRenderer from "@/features/comments/CommentDocumentRenderer.vue";

const props = withDefaults(defineProps<{ comment: CommentItem; replies?: CommentItem[]; replyCursor?: string | null; loaded?: boolean; loading?: boolean; isPostOwner?: boolean }>(), { replies:()=>[], replyCursor:null, loaded:false, loading:false, isPostOwner:false });
const emit = defineEmits<{
  reply:[comment:CommentItem]; load:[comment:CommentItem]; "load-more":[comment:CommentItem]; like:[comment:CommentItem]; edit:[comment:CommentItem]; remove:[comment:CommentItem]; pin:[comment:CommentItem]; hide:[comment:CommentItem]; report:[comment:CommentItem]; copy:[comment:CommentItem];
}>();
function authorName(comment:CommentItem){return comment.author?.displayName||comment.authorName||comment.author?.username||"@user";}
function tombstone(comment:CommentItem){return comment.tombstone||Boolean(comment.deletedAt)||comment.status==="DELETED"||comment.status==="HIDDEN";}
function tombstoneLabel(comment:CommentItem){return comment.status==="HIDDEN"?"Комментарий скрыт автором проекта":"Комментарий удалён";}
function formattedTime(value?:string|null){return value?new Intl.DateTimeFormat(undefined,{dateStyle:"medium",timeStyle:"short"}).format(new Date(value)):"";}
const missingReplyCount=computed(()=>Math.max(0,(props.comment.replyCount||0)-props.replies.length));
</script>

<template>
  <section class="comment-thread" :class="{'is-pinned':comment.pinnedAt}">
    <article :id="`comment-${comment.id}`" class="thread-row thread-row--root">
      <span v-if="replies.length || (comment.replyCount||0)>0" class="thread-row__connector" aria-hidden="true"></span>
      <div class="thread-row__avatar" aria-hidden="true">{{ authorName(comment).slice(0,1).toUpperCase() }}</div>
      <div class="thread-row__body">
        <header><strong>{{ authorName(comment) }}</strong><time :datetime="comment.createdAt||undefined">{{ formattedTime(comment.createdAt) }}</time><Pin v-if="comment.pinnedAt" :size="14" aria-label="Закреплено" /><button type="button" aria-label="Пожаловаться" @click="emit('report',comment)"><MoreHorizontal :size="17" /></button></header>
        <p v-if="tombstone(comment)" class="thread-row__tombstone">{{ tombstoneLabel(comment) }}</p>
        <CommentDocumentRenderer v-else :document="comment.document" :text="comment.text" :attachments="comment.attachments||comment.assets||[]" />
        <footer v-if="!tombstone(comment)">
          <button type="button" :class="{active:comment.likedByViewer}" aria-label="Нравится" @click="emit('like',comment)"><Heart :size="15" :fill="comment.likedByViewer?'currentColor':'none'" /><span>{{ comment.likeCount||'' }}</span></button>
          <button type="button" aria-label="Ответить" @click="emit('reply',comment)"><Reply :size="15" /></button><button type="button" aria-label="Скопировать ссылку" @click="emit('copy',comment)"><Link2 :size="15" /></button>
          <button v-if="isPostOwner" type="button" aria-label="Закрепить" @click="emit('pin',comment)"><Pin :size="15" /></button><button v-if="isPostOwner" type="button" aria-label="Скрыть" @click="emit('hide',comment)"><EyeOff :size="15" /></button>
          <button type="button" @click="emit('edit',comment)">Изменить</button><button type="button" aria-label="Удалить" @click="emit('remove',comment)"><Trash2 :size="15" /></button>
        </footer>
        <button v-if="(comment.replyCount||0)>0&&!loaded" class="thread-row__load" type="button" :disabled="loading" @click="emit('load',comment)"><ChevronDown :size="16" />{{ loading?'Загружаем':`Ещё ${comment.replyCount} ответов` }}</button>
      </div>
    </article>
    <article v-for="reply in replies" :id="`comment-${reply.id}`" :key="reply.id" class="thread-row thread-row--reply">
      <div class="thread-row__avatar" aria-hidden="true">{{ authorName(reply).slice(0,1).toUpperCase() }}</div>
      <div class="thread-row__body">
        <header><strong>{{ authorName(reply) }}</strong><time :datetime="reply.createdAt||undefined">{{ formattedTime(reply.createdAt) }}</time><button type="button" aria-label="Пожаловаться" @click="emit('report',reply)"><MoreHorizontal :size="17" /></button></header>
        <p v-if="tombstone(reply)" class="thread-row__tombstone">{{ tombstoneLabel(reply) }}</p>
        <template v-else><p v-if="reply.replyToId" class="thread-row__mention">Ответ участнику ветки</p><CommentDocumentRenderer :document="reply.document" :text="reply.text" :attachments="reply.attachments||reply.assets||[]" /></template>
        <footer v-if="!tombstone(reply)"><button type="button" :class="{active:reply.likedByViewer}" aria-label="Нравится" @click="emit('like',reply)"><Heart :size="15" :fill="reply.likedByViewer?'currentColor':'none'" /><span>{{ reply.likeCount||'' }}</span></button><button type="button" aria-label="Ответить" @click="emit('reply',reply)"><Reply :size="15" /></button><button type="button" aria-label="Скопировать ссылку" @click="emit('copy',reply)"><Link2 :size="15" /></button><button v-if="isPostOwner" type="button" aria-label="Скрыть" @click="emit('hide',reply)"><EyeOff :size="15" /></button><button type="button" @click="emit('edit',reply)">Изменить</button><button type="button" aria-label="Удалить" @click="emit('remove',reply)"><Trash2 :size="15" /></button></footer>
      </div>
    </article>
    <button v-if="replyCursor" class="thread-row__load thread-row__load--more" type="button" :disabled="loading" @click="emit('load-more',comment)"><ChevronDown :size="16" />{{ loading?'Загружаем':`Ещё ${missingReplyCount} ответов` }}</button>
  </section>
</template>

<style scoped>
.comment-thread{display:grid;padding:7px 0 11px}.comment-thread.is-pinned{margin:0 -10px;padding:11px 10px;border-radius:16px;background:#eef1f4}.thread-row{position:relative;display:grid;grid-template-columns:38px minmax(0,1fr);gap:10px}.thread-row--reply{margin-top:10px;padding-left:20px}.thread-row__connector{position:absolute;left:17px;top:37px;bottom:-12px;width:2px;border-radius:2px;background:#d5dae0}.thread-row__avatar{z-index:1;display:grid;place-items:center;width:34px;height:34px;border-radius:50%;background:#e3e7eb;color:#657181;font-size:12px;font-weight:900}.thread-row--reply .thread-row__avatar{width:30px;height:30px}.thread-row__body{min-width:0;display:grid;gap:6px;padding-bottom:3px}.thread-row header{display:flex;align-items:center;gap:7px;color:#818a96}.thread-row header strong{overflow:hidden;color:#39414b;font-size:13px;font-weight:900;text-overflow:ellipsis;white-space:nowrap}.thread-row time{font-size:11px;font-weight:700}.thread-row header button{margin-left:auto}.thread-row button{border:0;background:transparent;color:#77818e;cursor:pointer}.thread-row__tombstone{margin:0;color:#939aa4;font-size:13px;font-style:italic}.thread-row__mention{margin:0;color:#6379ad;font-size:11px;font-weight:900}.thread-row footer{display:flex;align-items:center;flex-wrap:wrap;gap:3px}.thread-row footer button,.thread-row__load{display:inline-flex;align-items:center;gap:4px;min-height:28px;padding:0 7px;border-radius:999px;font:800 11px/1 inherit}.thread-row footer button:hover,.thread-row footer button:focus-visible,.thread-row__load:hover,.thread-row__load:focus-visible{background:#e6e9ed}.thread-row footer button.active{color:#d64b72}.thread-row__load{justify-self:start;color:#586575}.thread-row__load--more{margin:6px 0 0 58px}.thread-row:target{outline:2px solid #4267dc;outline-offset:5px;border-radius:10px}
</style>
