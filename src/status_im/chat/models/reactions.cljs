(ns status-im.chat.models.reactions
  (:require [status-im.constants :as constants]
            [re-frame.core :as re-frame]
            [status-im.utils.fx :as fx]
            [taoensso.timbre :as log]
            [status-im.transport.message.protocol :as message.protocol]
            [status-im.data-store.reactions :as data-store.reactions]))

(defn process-reactions
  [reactions new-reactions]
  ;; TODO(Ferossgp): handling own reaction in subscription could be expensive,
  ;; for better performance we can here separate own reaction into 2 maps
  (reduce
   (fn [acc {:keys [chat-id message-id emoji-id emoji-reaction-id retracted]
             :as   reaction}]
     ;; NOTE(Ferossgp): For a better performance, better to not keep in db all retracted reactions
     ;; retraction will always come after the reaction so there shouldn't be a conflict
     (if retracted
       (update-in acc [chat-id message-id emoji-id] dissoc emoji-reaction-id)
       (assoc-in acc [chat-id message-id emoji-id emoji-reaction-id] reaction)))
   reactions
   new-reactions))

(defn- earlier-than-deleted-at?
  [{:keys [db]} {:keys [chat-id clock-value]}]
  (let [{:keys [deleted-at-clock-value]}
        (get-in db [:chats chat-id])]
    (>= deleted-at-clock-value clock-value)))

(fx/defn receive-signal
  [{:keys [db] :as cofx} reactions]
  (let [reactions (filter (partial earlier-than-deleted-at? cofx) reactions)]
    {:db (update db :reactions process-reactions reactions)}))

(fx/defn load-more-reactions
  [{:keys [db] :as cofx} cursor]
  (when-let [current-chat-id (:current-chat-id db)]
    (when-let [session-id (get-in db [:pagination-info current-chat-id :messages-initialized?])]
      (data-store.reactions/reactions-by-chat-id-rpc
       current-chat-id
       cursor
       constants/default-number-of-messages
       #(re-frame/dispatch [::reactions-loaded current-chat-id session-id %])
       #(log/error "failed loading reactions" current-chat-id %)))))

(fx/defn reactions-loaded
  {:events [::reactions-loaded]}
  [{{:keys [current-chat-id] :as db} :db}
   chat-id
   session-id
   reactions]
  (when-not (or (nil? current-chat-id)
                (not= chat-id current-chat-id)
                (and (get-in db [:pagination-info current-chat-id :messages-initialized?])
                     (not= session-id
                           (get-in db [:pagination-info current-chat-id :messages-initialized?]))))
    (let [reactions-w-chat-id (map #(assoc % :chat-id chat-id) reactions)]
      {:db (update db :reactions process-reactions reactions-w-chat-id)})))


;; Send reactions


(fx/defn send-emoji-reaction
  {:events [::send-emoji-reaction]}
  [{{:keys [current-chat-id] :as db} :db :as cofx} reaction]
  (message.protocol/send-reaction cofx
                                  (assoc reaction :chat-id current-chat-id)))

(fx/defn send-retract-emoji-reaction
  {:events [::send-emoji-reaction-retraction]}
  [{{:keys [current-chat-id reactions] :as db} :db :as cofx} reaction]
  (message.protocol/send-retract-reaction cofx
                                          (assoc reaction :chat-id current-chat-id)))

(fx/defn receive-one
  {:events [::receive-one]}
  [{:keys [db]} reaction]
  {:db (update db :reactions process-reactions [reaction])})

(defn message-reactions [current-public-key reactions]
  (reduce
   (fn [acc [emoji-id reactions]]
     (if (pos? (count reactions))
       (let [own (first (filter (fn [[_ {:keys [from]}]]
                                  (= from current-public-key)) reactions))]
         (conj acc {:emoji-id          emoji-id
                    :own               (boolean (seq own))
                    :emoji-reaction-id (:emoji-reaction-id (second own))
                    :quantity          (count reactions)}))
       acc))
   []
   reactions))
