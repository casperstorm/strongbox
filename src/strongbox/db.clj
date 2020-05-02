(ns strongbox.db
  (:require
   [taoensso.timbre :refer [log debug info warn error spy]]
   ;;[strongbox.utils :as utils :refer [uuid]]
   [crux.api :as crux]
   [clojure.java.io :as io]))

(defn uuid
  []
  (java.util.UUID/randomUUID))


;;


(defn to-crux-doc
  [blob]
  (cond
    ;; data blob already has a crux id
    ;; prefer that over any id it may have picked up and pass it through
    (and (map? blob)
         (contains? blob :crux.db/id)) (dissoc blob :id)

    ;; data blob has an id but no crux id, rename :id to crux id
    (and (map? blob)
         (contains? blob :id)) (clojure.set/rename-keys blob {:id :crux.db/id})

    ;; given something that isn't a map
    ;; wrap in a map, give it an id and pass it through
    (not (map? blob)) {:crux.db/id (uuid) :data blob}

    ;; otherwise, it *is* a map but is lacking an id or a crux id
    :else (assoc blob :crux.db/id (uuid))))

(defn from-crux-doc
  [result]
  (when result
    (if (map? result)
      (clojure.set/rename-keys result {:crux.db/id :id})

      ;; ... ? just issue a warning and pass it through
      (do
        (warn (str "got unknown type attempting to coerce result from crux db:" (type result)))
        result))))

(defn -to-put
  [blob]
  [:crux.tx/put (to-crux-doc blob)])

(defn put
  [node blob]
  (crux/submit-tx node [[:crux.tx/put (to-crux-doc blob)]]))

(defn put-many
  [node doc-list]
  (crux/submit-tx node (mapv -to-put doc-list)))

(defn put+wait
  [node blob]
  (crux/await-tx node (put blob)))

(defn get-by-id
  [node id]
  (from-crux-doc (crux/entity (crux/db node) id)))

(defn query
  [node query]
  (crux/q (crux/db node) query))

(defn query-by-type
  [node type-kw]
  (query node '{:find [e]
                :where [[e :type type-kw]]}))

(defn stored-query
  "common queries we can call by keyword"
  [node query-kw & [arg-list]]
  (let [query-map {;; todo, obviously.
                   :catalogue-size (constantly 0)}]
    (if-let [query-fn (query-kw query-map)]
      (query-fn node)
      (error "query not found:" (name query-kw)))))

(defn start
  "initialises the database, returning something that can be used to access it later"
  []
  (crux/start-node {:crux.node/topology '[crux.standalone/topology]}))
