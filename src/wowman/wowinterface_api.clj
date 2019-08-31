(ns wowman.wowinterface-api
  (:require
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [wowman
    [utils :as utils]
    [specs :as sp]
    [http :as http]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]))

(def wowinterface-api "https://api.mmoui.com/v3/game/WOW")

(defn-spec api-uri ::sp/uri
  [path string?, & args (s/* any?)]
  (str wowinterface-api (apply format path args)))

(defn expand-summary
  [addon-summary]
  (let [url (api-uri "/filedetails/%s.json" (:source-id addon-summary))
        ;; returns a map nested in a list? todo: are there any conditions where more than one item is returned?
        result-list (-> url http/download utils/from-json)
        result (first result-list)]
    (when (> (count result-list) 1)
      (warn "wowinterface api returned more than one result for addon with :source-id" (:source-id addon-summary)))
    (merge addon-summary {:download-uri (str "https://cdn.wowinterface.com/downloads/getfile.php?id=" (:source-id addon-summary))
                          :version (:UIVersion result)})))

(st/instrument)
