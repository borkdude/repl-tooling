(ns repl-tooling.editor-integration.embedded-clojurescript
  (:require [repl-tooling.features.shadow-cljs :as shadow]
            [repl-tooling.integrations.connection :as conn]))

(def trs {:no-build-id "There's no build ID detected on shadow-cljs file"
          :no-shadow-file "File shadow-cljs.edn not found"
          :no-shadow "This project is not a shadow-cljs, can't connect to CLJS REPL"
          :workers-empty "No shadow-cljs workers running"
          :no-worker "No worker for build"})

(defn- notify! [notify params]
  (notify params)
  (. js/Promise resolve nil))

(defn- treat-error [error notify]
  (notify! notify {:type :error
                   :title "Error connecting to ClojureScript"
                   :message (trs error "Unknown Error")})
  nil)

(defn- save-repl-info! [state target repl]
  (swap! state
         (fn [s] (-> s
                     (assoc :cljs/repl repl)
                     (assoc-in [:repl/info :cljs/repl-env]
                               `(shadow.cljs.devtools.api/compiler-env ~target))))))

(defn- connect-and-update-state! [state opts target upgrade-cmd]
  (let [{:keys [notify on-result on-stdout]} opts
        {:keys [host port]} (:repl/info @state)
        after-connect #(if-let [error (:error %)]
                         (treat-error error notify)
                         (do
                           (save-repl-info! state target %)
                           (notify! notify
                                    {:type :info
                                     :title "Connected to ClojureScript"
                                     :message (str "Connected to Shadow-CLJS target " target)})
                           %))]
    (if target
      ; FIXME: feature toggle
      (if upgrade-cmd
        (.then (conn/connect-self-hosted! {:host host
                                           :port port
                                           :code upgrade-cmd
                                           :on-result #(and on-result (on-result %))
                                           :on-stdout #(and on-stdout (on-stdout %))})
               after-connect)
        (.then (conn/connect-shadow! (assoc opts
                                            :host host
                                            :port port
                                            :build-id target))
               after-connect))
      (notify! notify {:type :warn
                       :title "No option selected"
                       :message "Please select a valid target for Shadow-CLJS"}))))

(defn- choose-id! [state {:keys [prompt] :as opts} commands use-new?]
  (.. (prompt {:title "Multiple Shadow-CLJS targets"
               :message "Choose the build target that you want to connect"
               :arguments (->> commands keys (map (fn [id] {:key id :value (name id)})))})
      (then #(connect-and-update-state! state opts
                                        (keyword %)
                                        (when-not use-new? (->> % keyword (get commands)))))))

(defn- connect-embedded [state {:keys [get-config notify] :as opts} use-new?]
  (let [commands (shadow/command-for (:project-paths (get-config)))]
    (if-let [error (:error commands)]
      (treat-error error notify)
      (case (count commands)
        0 (treat-error :no-build-id notify)
        1 (.then (connect-and-update-state! state opts
                                            (-> commands keys first)
                                            (when-not use-new? (-> commands vals first))))
        (choose-id! state opts commands use-new?)))))

(defn connect! [state {:keys [notify] :as opts} use-new?]
  (cond
    (:cljs/repl @state)
    (notify! notify {:type :warn
                     :title "REPL already connected"
                     :message (str "REPL is already connected.\n\n"
                                   "Please, disconnect the current REPL "
                                   "if you want to connect to another.")})

    (:clj/aux @state)
    (connect-embedded state opts use-new?)

    :else
    (notify! notify {:type :warn
                     :title "REPL not connected"
                     :message (str "To connect a self-hosted REPL, "
                                   "you first need to connect a Clojure REPL")})))
