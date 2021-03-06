(ns strongbox.catalogue
  (:require
   [flatland.ordered.map :as omap]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as log :refer [debug info warn error spy]]
   [taoensso.tufte :as tufte :refer [p profile]]
   [java-time]
   [strongbox
    [tags :as tags]
    [utils :as utils :refer [todt utcnow]]
    [specs :as sp]
    [tukui-api :as tukui-api]
    [curseforge-api :as curseforge-api]
    [wowinterface-api :as wowinterface-api]
    [github-api :as github-api]]))

(defn-spec expand-summary (s/or :ok (s/merge :addon/expandable :addon/source-updates), :error nil?)
  "fetches updates from the addon host for the given `addon`.
  hosts handle the game track in different ways."
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [dispatch-map {"curseforge" curseforge-api/expand-summary
                      "wowinterface" wowinterface-api/expand-summary
                      "github" github-api/expand-summary
                      "tukui" tukui-api/expand-summary
                      "tukui-classic" tukui-api/expand-summary-classic
                      nil (fn [_ _] (error "malformed addon:" (utils/pprint addon)))}
        key (:source addon)]
    (try
      (if-not (contains? dispatch-map key)
        (error (format "addon '%s' is from source '%s' that is unsupported" (:label addon) key))
        (if-let [source-updates ((get dispatch-map key) addon game-track)]
          (merge addon source-updates)
          ;; "no release found for 'adibags' (retail) on github"
          (warn (format "no release found for '%s' (%s) on %s"
                        (:name addon)
                        (utils/kw2str game-track)
                        (:source addon)))))
      (catch Exception e
        (error e "unhandled exception attempting to expand addon summary")
        (error "please report this! https://github.com/ogri-la/strongbox/issues")))))

;;

(defn-spec format-catalogue-data :catalogue/catalogue
  "returns a correctly formatted catalogue given a list of addons and a created and updated date"
  [addon-list :addon/summary-list, created-date ::sp/ymd-dt]
  (let [addon-list (mapv #(into (omap/ordered-map) (sort %))
                         (sort-by :name addon-list))]
    {:spec {:version 2}
     :datestamp created-date
     :total (count addon-list)
     :addon-summary-list addon-list}))

(defn-spec catalogue-v1-coercer :catalogue/catalogue
  "converts wowman-era specification 1 catalogues, coercing them to specification version 2 catalogues"
  [catalogue-data map?]
  (let [row-coerce (fn [row]
                     (-> row
                         (assoc :tag-list (tags/category-list-to-tag-list (:source row) (:category-list row)))
                         (dissoc :category-list)))]
    (-> catalogue-data
        (update-in [:addon-summary-list] #(into [] (map row-coerce) %))
        (update-in [:spec :version] inc))))

(defn -read-catalogue-value-fn
  "used to transform catalogue values as the json is read. applies to both v1 and v2 catalogues."
  [key val]
  (case key
    :game-track-list (mapv keyword val)
    :tag-list (mapv keyword val)
    :description (utils/safe-subs val 255) ;; no database anymore, no hard failures on value length?

    ;; returning the function itself ensures element is removed from the result entirely
    :alt-name -read-catalogue-value-fn

    ;; catalogue-level in v1 catalogue, not the addon-level 'updated-date' that we should keep
    :updated-datestamp -read-catalogue-value-fn

    val))

(defn-spec -read-catalogue (s/or :ok :catalogue/catalogue, :error nil?)
  "reads the catalogue of addon data at the given `catalogue-path`.
  supports reading legacy catalogues by dispatching on the `[:spec :version]` number."
  [catalogue-path ::sp/file, opts map?]
  (p :catalogue
     (let [key-fn (fn [k]
                    (case k
                      "uri" :url
                      ;;"category-list" :tag-list ;; pushed back into v1 post-processing
                      (keyword k)))
           value-fn -read-catalogue-value-fn ;; defined 'outside' so it can reference itself
           opts (merge opts {:key-fn key-fn :value-fn value-fn})
           catalogue-data (p :catalogue:load-json-file
                             (utils/load-json-file-safely catalogue-path opts))]

       (when-not (empty? catalogue-data)
         (if (-> catalogue-data :spec :version (= 1)) ;; if v1 catalogue, coerce
           (p :catalogue:v1-coercer
              (utils/nilable (catalogue-v1-coercer catalogue-data)))
           catalogue-data)))))

(defn validate
  "validates the given data as a `:catalogue/catalogue`, returning nil if data is invalid"
  [catalogue]
  (sp/valid-or-nil :catalogue/catalogue catalogue))

(defn read-catalogue
  "reads catalogue at given `path` and validates the result, regardless of spec instrumentation.
  returns `nil` if catalogue is invalid."
  ([path]
   (read-catalogue path {}))
  ([path opts]
   (-> path (-read-catalogue opts) validate)))

(defn-spec write-catalogue ::sp/extant-file
  "write catalogue to given `output-file` as JSON. returns path to output file"
  [catalogue-data :catalogue/catalogue, output-file ::sp/file]
  (if (some->> catalogue-data validate (utils/dump-json-file output-file))
    (do
      (info "wrote" output-file)
      output-file)
    (error "catalogue data is invalid, refusing to write:" output-file)))

(defn-spec new-catalogue :catalogue/catalogue
  "convenience. returns a new catalogue with datestamp of 'now' given a list of addon summaries"
  [addon-list :addon/summary-list]
  (format-catalogue-data addon-list (utils/datestamp-now-ymd)))

(defn-spec write-empty-catalogue! ::sp/extant-file
  "writes a stub catalogue to the given `output-file`"
  [output-file ::sp/file]
  (write-catalogue (new-catalogue []) output-file))

;;

(defn-spec parse-user-string (s/or :ok :addon/summary, :error nil?)
  "given a string, figures out the addon source (github, etc) and dispatches accordingly."
  [uin string?]
  (let [dispatch-map {"github.com" github-api/parse-user-string
                      "www.github.com" github-api/parse-user-string}] ;; alias
    (try
      (when-let [f (some->> uin utils/unmangle-https-url java.net.URL. .getHost (get dispatch-map))]
        (info "inspecting:" uin)
        (f uin))
      (catch java.net.MalformedURLException mue
        (debug "not a url")))))

(defn-spec merge-catalogues (s/or :ok :catalogue/catalogue, :error nil?)
  "merges catalogue `cat-b` over catalogue `cat-a`.
  earliest creation date preserved.
  latest updated date preserved.
  addon-summary-list is unique by `:source` and `:source-id` with differing values replaced by those in `cat-b`"
  [cat-a (s/nilable :catalogue/catalogue), cat-b (s/nilable :catalogue/catalogue)]
  (let [matrix {;;[true true] ;; two non-empty catalogues, ideal case
                [true false] cat-a ;; cat-b empty, return cat-a
                [false true] cat-b ;; vice versa
                [false false] nil}
        not-empty? (complement empty?)
        key [(not-empty? cat-a) (not-empty? cat-b)]]
    (if (contains? matrix key)
      (get matrix key)
      (let [created-date (first (sort [(:datestamp cat-a) (:datestamp cat-b)])) ;; earliest wins
            addons-a (:addon-summary-list cat-a)
            addons-b (:addon-summary-list cat-b)
            addon-summary-list (->> (concat addons-a addons-b) ;; join the two lists
                                    (group-by (juxt :source-id :source)) ;; group by the key
                                    vals ;; drop the map
                                    (map (partial apply merge))) ;; merge (not replace) the groups into single maps
            ]
        (format-catalogue-data addon-summary-list created-date)))))

;; 

(defn de-dupe-wowinterface
  "at time of writing, wowinterface has 5 pairs of duplicate addons with slightly different labels
  for each pair we'll pick the most recently updated.
  these pairs *may* get picked up and filtered out further down when comparing merged catalogues,
  depending on the time difference between the two updated dates"
  [addon-list]
  (let [de-duper (fn [[_ group-list]]
                   (if (> (count group-list) 1)
                     (do
                       (warn "wowinterface: multiple addons slugify to the same :name" (utils/pprint group-list))
                       (last (sort-by :updated-date group-list)))
                     (first group-list)))
        addon-groups (group-by :name addon-list)]
    (mapv de-duper addon-groups)))

;;

(defn-spec shorten-catalogue (s/or :ok :catalogue/catalogue, :problem nil?)
  "reads the catalogue at the given `path` and returns a truncated version where all addons unmaintained addons are removed.
  an addon is considered unmaintained if it hasn't been updated since the beginning of the previous expansion"
  [full-catalogue-path ::sp/extant-file, cutoff ::sp/inst]
  (let [{:keys [addon-summary-list datestamp]} (read-catalogue full-catalogue-path)
        unmaintained? (fn [addon]
                        (let [dtobj (java-time/zoned-date-time (:updated-date addon))]
                          (java-time/before? dtobj (utils/todt cutoff))))]
    (when addon-summary-list
      (format-catalogue-data (remove unmaintained? addon-summary-list) datestamp))))
