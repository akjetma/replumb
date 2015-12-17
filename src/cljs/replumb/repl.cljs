(ns replumb.repl
  (:refer-clojure :exclude [load-file ns-publics])
  (:require-macros [cljs.env.macros :refer [with-compiler-env]])
  (:require [cljs.js :as cljs]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :as rt]
            [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.repl :as repl]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as s]
            [replumb.common :as common]
            [replumb.doc-maps :as docs]
            [replumb.load :as load]
            [replumb.browser :as browser]
            [replumb.nodejs :as nodejs]))

;;;;;;;;;;;;;
;;; State ;;;
;;;;;;;;;;;;;

;; This is the compiler state atom. Note that cljs/eval wants exactly an atom.
(defonce st (cljs/empty-state))

(defonce app-env (atom {:current-ns 'cljs.user
                        :last-eval-warning nil
                        :initializing? false
                        :needs-init? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Util fns - many from mfikes/plank ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ex-info-data "The ex-info data for this file" {:tag ::error})

(defn current-ns
  "Return the current namespace, as a symbol."
  []
  (:current-ns @app-env))

(defn known-namespaces
  []
  (keys (:cljs.analyzer/namespaces @st)))

(defn ns-publics
  "Given a namespace return all the public var analysis maps. Analagous to
  clojure.core/ns-publics but returns var analysis maps not vars."
  ([ns]
   (ns-publics env/*compiler* ns))
  ([state ns]
   {:pre [(symbol? ns)]}
   (->> (merge
         (get-in @state [::ana/namespaces ns :macros])
         (get-in @state [::ana/namespaces ns :defs]))
        (remove (fn [[k v]] (:private v)))
        (into {}))))

(defn get-namespace
  "Given a namespace symbol, returns its AST"
  [sym]
  (get-in @st [:cljs.analyzer/namespaces sym]))

(defn get-empty-aenv
  []
  (assoc (ana/empty-env)
         :ns (get-namespace (:current-ns @app-env))
         :context :expr))

(defn get-goog-path
  "Given a Google Closure provide / Clojure require (e.g. goog.string),
  returns the path to the actual file (without extension)."
  [provide]
  (get-in @app-env [:goog-provide->path provide]))

(defn map-keys
  [f m]
  (reduce-kv (fn [r k v] (assoc r (f k) v)) {} m))

(defn repl-read-string
  "Try to read a string binding all the standard data readers. This
  function throws if a valid form cannot be found."
  [line]
  (binding [r/*data-readers* tags/*cljs-data-readers*]
    (r/read-string {:read-cond :allow :features #{:cljs}} line)))

(defn ns-form?
  [form]
  (and (seq? form) (= 'ns (first form))))

(defn extract-namespace
  [source]
  (let [first-form (repl-read-string source)]
    (when (ns-form? first-form)
      (second first-form))))

(defn resolve
  "From cljs.analizer.api.clj. Given an analysis environment resolve a
  var. Analogous to clojure.core/resolve"
  [opts env sym]
  {:pre [(map? env) (symbol? sym)]}
  (try
    (when (:verbose opts)
      (common/debug-prn "Calling cljs.analyzer/resolve-var..."))
    (ana/resolve-var env sym ana/confirm-var-exist-warning)
    (catch :default e
      (when (:verbose opts)
        (common/debug-prn "Exception caught in resolve: " e))
      (try
        (when (:verbose opts)
          (common/debug-prn "Calling cljs.analyzer/resolve-macro-var..."))
        (ana/resolve-macro-var env sym)
        (catch :default e
          (when (:verbose opts)
            (common/debug-prn "Exception caught in resolve: " e)))))))

(defn get-var
  [opts env sym]
  (let [var (with-compiler-env st (resolve opts env sym))
        var (or var
                (if-let [macro-var (with-compiler-env st
                                     (resolve opts env (symbol "cljs.core$macros" (name sym))))]
                  (update (assoc macro-var :ns 'cljs.core)
                          :name #(symbol "cljs.core" (name %)))))]
    (if (= (namespace (:name var)) (str (:ns var)))
      (update var :name #(symbol (name %)))
      var)))

(def replumb-repl-special-set
  '#{in-ns require require-macros import load-file doc source pst dir})

(defn repl-special?
  [form]
  (and (seq? form) (replumb-repl-special-set (first form))))

(defn make-base-eval-opts!
  "Gets the base set of evaluation options. The 1-arity function
  specifies opts that override default. No check here if opts are
  valid."
  ([]
   (make-base-eval-opts! {}))
  ([opts]
   {:ns (:current-ns @app-env)
    :context :expr
    :source-map false
    :def-emits-var true
    :load (:load-fn! opts)
    :eval cljs/js-eval
    :verbose (or (:verbose opts) false)
    :static-fns false}))

(defn self-require?
  [specs]
  (some
   (fn [quoted-spec-or-kw]
     (and (not (keyword? quoted-spec-or-kw))
          (let [spec (second quoted-spec-or-kw)
                ns (if (sequential? spec)
                     (first spec)
                     spec)]
            (= ns @current-ns))))
   specs))

(defn canonicalize-specs
  [specs]
  (letfn [(canonicalize [quoted-spec-or-kw]
            (if (keyword? quoted-spec-or-kw)
              quoted-spec-or-kw
              (as-> (second quoted-spec-or-kw) spec
                (if (vector? spec) spec [spec]))))]
    (map canonicalize specs)))

;; from https://github.com/mfikes/planck/commit/fe9e7b3ee055930523af1ea3ec9b53407ed2b8c8
(defn purge-ns-analysis-cache!
  [st ns]
  (swap! st update-in [::ana/namespaces] dissoc ns))

(defn purge-ns!
  [st ns]
  (purge-ns-analysis-cache! st ns)
  (swap! cljs.js/*loaded* disj ns))

(defn process-reloads!
  [specs]
  (if-let [k (some #{:reload :reload-all} specs)]
    (let [specs (remove #{k} specs)]
      (if (= k :reload-all)
        (doseq [ns @cljs.js/*loaded*]
          (purge-ns! st ns))
        (doseq [ns (map first specs)]
          (purge-ns! st ns)))
      specs)
    specs))

(defn make-ns-form
  [kind specs target-ns]
  (if (= kind :import)
    (with-meta `(~'ns ~target-ns
                  (~kind
                   ~@(map (fn [quoted-spec-or-kw]
                            (if (keyword? quoted-spec-or-kw)
                              quoted-spec-or-kw
                              (second quoted-spec-or-kw)))
                          specs)))
      {:merge true :line 1 :column 1})
    (with-meta `(~'ns ~target-ns
                  (~kind
                   ~@(-> specs canonicalize-specs process-reloads!)))
      {:merge true :line 1 :column 1})))

(defn goog-deps-map
  "Given the content of goog/deps.js file, create a map
  provide->path (without extension) of Google dependencies.

  Adapted from planck:
  https://github.com/mfikes/planck/blob/master/planck-cljs/src/planck/repl.cljs#L438-L451"
  [deps-js-content]
  (let [paths-to-provides (map (fn [[_ path provides]]
                                 [path (map second (re-seq #"'(.*?)'" provides))])
                               (re-seq #"\ngoog\.addDependency\('(.*)', \[(.*?)\].*"
                                       deps-js-content))]
    (into {} (for [[path provides] paths-to-provides
                   provide provides]
               [(symbol provide) (str "goog/" (second (re-find #"(.*)\.js$" path)))]))))

(defn make-load-fn
  "Makes a load function that will read from a sequence of src-paths
  using a supplied read-file-fn. It returns a cljs.js-compatible
  *load-fn*.

  Read-file-fn is an async 2-arity function (fn [file-path src-cb] ...)
  where src-cb is itself a function (fn [source] ...) that needs to be
  called with the full source of the library (as string)."
  [verbose? src-paths read-file-fn]
  (fn [{:keys [name macros path] :as load-map} cb]
    (cond
      (load/skip-load? load-map) (load/fake-load-fn! load-map cb)
      (re-matches #"^goog/.*" path) (if-let [goog-path (get-goog-path name)]
                                      (load/read-files-and-callback! verbose?
                                                                     (load/goog-file-paths-to-try src-paths goog-path)
                                                                     read-file-fn
                                                                     cb)
                                      (cb nil))
      :else (load/read-files-and-callback! verbose?
                                           (load/file-paths-to-try src-paths macros path)
                                           read-file-fn
                                           cb))))

;;;;;;;;;;;;;;;;
;;; Options ;;;;
;;;;;;;;;;;;;;;;

(def valid-opts-set
  "Set of valid option used for external input validation."
  #{:verbose :warning-as-error :target :init-fn!
    :load-fn! :read-file-fn! :src-paths})

(defn valid-opts
  "Validate the input user options. Returns a new map without invalid
  ones according to valid-opts-set."
  [user-opts]
  (into {} (filter (comp valid-opts-set first) user-opts)))

(defn add-default-opts
  "Given user provided options, conjoins the default option map for
  its :target (string or keyword). Defaults to conjoining :default (browser,
  aka :js target)."
  [opts user-opts]
  (merge opts (condp = (keyword (:target user-opts))
                :nodejs nodejs/default-opts
                browser/default-opts)))

(defn add-load-fn
  "Given current and user options, if :load-fn! is present in user-opts,
  conjoins it. Try to create and conjoin one from :src-paths
  and :read-file-fn! otherwise. Conjoins nil if it cannot."
  [opts user-opts]
  (assoc opts :load-fn!
         (or (:load-fn! user-opts)
             (let [read-file-fn (:read-file-fn! user-opts)
                   src-paths (:src-paths user-opts)]
               (if (and read-file-fn (sequential? src-paths))
                 (make-load-fn (:verbose user-opts)
                               (into [] src-paths)
                               read-file-fn)
                 (when (:verbose user-opts)
                   (common/debug-prn "Invalid :read-file-fn! or :src-paths (is it a valid sequence?). Cannot create *load-fn*.")))))))

(defn add-init-fns
  "Given current and user options, returns a map containing a
  valid :init-fns,conjoining with the one in current if necessary."
  [opts user-opts]
  (update-in opts [:init-fns] (fn [init-fns]
                                (if-let [fn (:init-fn! user-opts)]
                                  (conj init-fns fn)
                                  init-fns))))

(defn normalize-opts
  "Process the user options. Returns the map that can be fed to
  read-eval-call."
  [user-opts]
  (let [vld-opts (valid-opts user-opts)]
    ;; AR - note the order here, the last always overrides
    (-> vld-opts
        (add-default-opts vld-opts)
        (add-load-fn vld-opts)
        (add-init-fns vld-opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Callback handling fns ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn success-map
  "Builds the map to return when the evaluation returned success.
  Supports the following options:

  * :no-pr-str-on-value avoids wrapping value in pr-str."
  ([opts form warning value]
   {:success? true
    :form form
    :warning warning
    :value (if-not (:no-pr-str-on-value opts)
             (pr-str value)
             value)}))

(defn error-map
  "Builds the map to return when the evaluation returned error."
  ([opts form warning error]
   {:success? false
    :form form
    :warning warning
    :error error}))

(defn reset-last-warning!
  []
  (swap! app-env assoc :last-eval-warning nil))

(defn custom-warning-handler
  "Handles the case when the evaluation returns a warning and can be
  passed as a warning handler when partially applied. At the moment it
  treats warnings as errors."
  [opts cb warning-type env extra]
  (when (:verbose opts)
    (common/debug-prn (str "Handling warning:\n" (with-out-str (pprint {:warning-type warning-type
                                                                        :env env
                                                                        :extra extra})))))
  (when (warning-type ana/*cljs-warnings*)
    (when-let [s (ana/error-message warning-type extra)]
      (swap! app-env assoc :last-eval-warning (ana/message env s)))))

(defn validated-call-back!
  [call-back! res]
  {:pre [(map? res)
         (find res :form)
         (or (find res :error) (find res :value))
         (or (and (find res :value) (get res :success?))
             (and (find res :error) (not (get res :success?))))
         (or (and (find res :value) (string? (get res :value)))
             (and (find res :error) (instance? js/Error (get res :error))))
         (or (not (find res :warning))
             (and (find res :warning)) (string? (get res :warning)))]}
  (call-back! res))

(defn validated-init-fn!
  [init-fn! res]
  {:pre [(map? res)
         (find res :form)
         (find res :ns)
         (= *target*  (get res :target))]}
  (init-fn! res))

(defn call-side-effect!
  "Execute the correct side effecting function from data.
  Handles :side-effect-fn!, :on-error-fn! and on-success-fn!."
  [data {:keys [value error]}]
  (if-let [f! (:side-effect-fn! data)]
    (f!)
    (if-not error
      (when-let [s! (:on-success-fn! data)] (s!))
      (when-let [e! (:on-error-fn! data)] (e!)))))

(defn warning-error-map!
  "Checks if there has been a warning and if so will return a new result
  map instead of the input one, potentially with a :warning key
  containing the warning message in it.

  The code paths are the following:

  - if the input map was already an :error, there will be no warning,
  the original :error is returned.
  - if the input map was a :value:
    - if (:warning-as-error opts) is truey, the new map will always
      contain it as :error, overriding the original.
    - if (:warning-as-error opts) is falsey, the new map will contain
      the warning as :warning along with the original :value"

  [opts {:keys [error] :as orig}]
  (if-let [warning-msg (:last-eval-warning @app-env)]
    (if-not error
      (if (not (:warning-as-error opts))
        (assoc orig :warning warning-msg)
        (let [warning-error (ex-info warning-msg ex-info-data)]
          (when (:verbose opts)
            (common/debug-prn "Erroring on last warning: " warning-msg))
          (common/wrap-error warning-error)))
      orig)
    orig))

(defn call-back!
  "Handles the evaluation result, calling the callback in the right way,
  based on the success or error of the evaluation. The res parameter
  expects the same map as ClojureScript's cljs.js callback,
  :value if success and :error if not. The data parameter might contain
  additional stuff:

  * :form the source form that has been eval-ed
  * :on-success-fn! 0-arity function that will be executed on success
  * :on-error-fn! 0-arity function that will be executed on error
  * :side-effect-fn! 0-arity function that if present will be executed
  for both success and error, effectively disabling the individual
  on-success-fn! and on-error-fn!

  Call-back! supports the following opts:

  * :verbose will enable the the evaluation logging, defaults to false.
  * :no-pr-str-on-value avoids wrapping successful value in a pr-str
  * :warning-as-error will consider a warning like an error

  Notes:
  1. The opts map passed here overrides the environment options.
  2. This function will also clear the :last-eval-warning flag in
  app-env.
  3. It will execute (:side-effect-fn!) or (on-success-fn!)
  and (on-error-fn!)  *before* the callback is called.

  ** Every function in this namespace should call call-back! as
  single point of exit. **"
  ([opts cb res]
   (call-back! opts cb {} res))
  ([opts cb data res]
   (when (:verbose opts)
     (common/debug-prn "Calling back!\n" (with-out-str (pprint {:opts (common/filter-fn-keys opts)
                                                                :data (common/filter-fn-keys data)
                                                                :res res}))))
   (let [new-map (warning-error-map! opts res)]
     (let [{:keys [value error warning]} new-map]
       (call-side-effect! data new-map)
       (reset-last-warning!)
       (if-not error
         (do (set! *e nil)
             (cb (success-map opts (:form data) warning value)))
         (do (set! *e error)
             (cb (error-map opts (:form data) warning error))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Processing fns - from mfikes/plank ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn process-require
  [opts cb data kind specs]
  ;; TODO - cannot find a way to handle (require something) correctly, note no quote
  (if-not (= 'quote (ffirst specs))
    (call-back! opts cb data (common/error-argument-must-be-symbol "require" ex-info-data))
    (let [is-self-require? (and (= :kind :require) (self-require? specs))
          [target-ns restore-ns] (if-not is-self-require?
                                   [(:current-ns @app-env) nil]
                                   ['cljs.user (:current-ns @app-env)])
          ns-form (make-ns-form kind specs target-ns)]
      (when (:verbose opts)
        (common/debug-prn "Processing" kind "via" (pr-str ns-form)))
      (cljs/eval st
                 ns-form
                 (make-base-eval-opts! opts)
                 (fn [{error :error}]
                   (call-back! opts cb
                               (merge data
                                      {:side-effect-fn! #(when is-self-require?
                                                           (swap! app-env assoc :current-ns restore-ns))})
                               (if error
                                 (common/wrap-error error)
                                 (common/wrap-success nil))))))))

(defn process-doc
  [opts cb data env sym]
  (call-back! (merge opts {:no-pr-str-on-value true})
              cb
              data
              (common/wrap-success
               (with-out-str
                 (cond
                   (docs/special-doc-map sym) (repl/print-doc (docs/special-doc sym))
                   (docs/repl-special-doc-map sym) (repl/print-doc (docs/repl-special-doc sym))
                   (get-namespace sym) (repl/print-doc (select-keys (get-namespace sym) [:name :doc]))
                   :else (repl/print-doc (get-var opts env sym)))))))

(defn process-pst
  [opts cb data expr]
  (if-let [expr (or expr '*e)]
    (cljs/eval st
               expr
               (make-base-eval-opts! opts)
               (fn [res]
                 (let [[opts msg] (if res
                                    [(assoc opts :no-pr-str-on-value true) (common/extract-message res true true)]
                                    [opts res])]
                   (call-back! opts cb data (common/wrap-success msg)))))
    (call-back! opts cb data (common/wrap-success nil))))

(defn process-in-ns
  [opts cb data ns-string]
  (cljs/eval
   st
   ns-string
   (make-base-eval-opts! opts)
   (fn [result]
     (if (and (map? result) (:error result))
       (call-back! opts cb data result)
       (let [ns-symbol result]
         (when (:verbose opts)
           (common/debug-prn "in-ns argument is symbol? " (symbol? ns-symbol)))
         (if-not (symbol? ns-symbol)
           (call-back! opts cb data (common/error-argument-must-be-symbol "in-ns" ex-info-data))
           (if (some (partial = ns-symbol) (known-namespaces))
             (call-back! opts cb
                         (merge data {:side-effect-fn! #(swap! app-env assoc :current-ns ns-symbol)})
                         (common/wrap-success nil))
             (let [ns-form `(~'ns ~ns-symbol)]
               (cljs/eval
                st
                ns-form
                (make-base-eval-opts! opts)
                (fn [error]
                  (call-back! opts
                              cb
                              (merge data {:on-success-fn! #(swap! app-env assoc :current-ns ns-symbol)})
                              (if error
                                (common/wrap-error error)
                                (common/wrap-success nil)))))))))))))

(defn fetch-source
  [{:keys [verbose read-file-fn!]} var paths-to-try cb]
  (load/read-files-and-callback! verbose
                                 paths-to-try
                                 read-file-fn!
                                 (fn [result]
                                   (if result
                                     (let [source (:source result)
                                           rdr (rt/source-logging-push-back-reader source)]
                                       (dotimes [_ (dec (:line var))] (rt/read-line rdr))
                                       (-> (r/read {:read-cond :allow :features #{:cljs}} rdr)
                                           meta
                                           :source
                                           common/wrap-success
                                           cb))
                                     (cb (common/wrap-success "nil"))))))

(defn process-source
  [opts cb data env sym]
  (let [var (get-var opts env sym)
        call-back (partial call-back! (merge opts {:no-pr-str-on-value true}) cb data)]
    (if-let [file-path (or (:file var) (:file (:meta var)))]
      (let [src-paths (:src-paths opts)
            ;; see discussion here: https://github.com/ScalaConsultants/replumb/issues/17#issuecomment-163832028
            ;; if (symbol? filepath) is true, filepath will contain the symbol of a namespace
            ;; eg. clojure.set
            paths-to-try (if (symbol? file-path)
                           (load/file-paths-to-try-from-ns-symbol src-paths file-path)
                           file-path)]
        (fetch-source opts var paths-to-try call-back))
      (call-back (common/wrap-success "nil")))))

(defn process-dir
  [opts cb data env sym]
  (let [vars (-> (ns-publics st sym) keys sort)
        call-back (partial call-back! (merge opts {:no-pr-str-on-value true}) cb data)]
    (if (seq vars)
      (call-back (common/wrap-success (s/join \newline vars)))
      (call-back (common/wrap-success "nil")))))

(defn process-repl-special
  [opts cb data expression-form]
  (let [argument (second expression-form)]
    (case (first expression-form)
      in-ns (process-in-ns opts cb data argument)
      require (process-require opts cb data :require (rest expression-form))
      require-macros (process-require opts cb data :require-macros (rest expression-form))
      import (process-require opts cb data :import (rest expression-form))
      doc (process-doc opts cb data (get-empty-aenv) env argument)
      source (process-source opts cb data (get-empty-aenv) argument)
      pst (process-pst opts cb data argument)
      dir (process-dir opts cb data (get-empty-aenv) argument)
      load-file (call-back! opts cb data (common/error-keyword-not-supported "load-file" ex-info-data))))) ;; (process-load-file argument opts)

(defn process-1-2-3
  [data expression-form value]
  (when-not (or ('#{*1 *2 *3 *e} expression-form)
                (ns-form? expression-form))
    (set! *3 *2)
    (set! *2 *1)
    (set! *1 value)))

;;;;;;;;;;;;;;;;;;;;;;
;;; Initialization ;;;
;;;;;;;;;;;;;;;;;;;;;;

(defn init-closure-index!
  "Create and swap in app-env a map from Google Closure provide string
  to their respective path (without extension).  It merges with the
  current map if many deps.js are on the source path, precedence to the
  last (as per merge)."
  [opts]
  (let [verbose? (:verbose opts)
        read-file! (:read-file-fn! opts)]
    (when verbose?
      (common/debug-prn "Discovering goog/deps.js in" (:src-paths opts)))
    (doseq [path (:src-paths opts)]
      (let [goog-deps-path (str (common/normalize-path path) "goog/deps.js")]
        (read-file! goog-deps-path
                    (fn [content]
                      (when content
                        (do (when verbose?
                              (common/debug-prn "Found valid" goog-deps-path))
                            (swap! app-env
                                   update :goog-provide->path
                                   merge (goog-deps-map content))))))))))

(defn init-repl!
  "The init-repl function. It uses the following opts keys:

  * :init-fns initialization function vector, it will be executed in
  order

  Data is passed from outside and will be forwarded to :init-fn!."
  [opts data]
  (when (:verbose opts)
    (common/debug-prn "Initializing REPL environment with data" (println data)))
  (assert (= cljs.analyzer/*cljs-ns* 'cljs.user))
  ;; Target/user init, we need at least one init-fn, the default init function
  (let [init-fns (:init-fns opts)]
    (assert (> (count init-fns) 0))
    (doseq [init-fn! init-fns]
      (init-fn! data)))
  ;; Building the closure index
  (init-closure-index! opts))

(defn update-to-initializing
  [old-app-env]
  (if (and (not (:initializing? old-app-env))
           (:needs-init? old-app-env))
    (assoc old-app-env :initializing? true)
    (assoc old-app-env :needs-init? false)))

(defn update-to-initialized
  [old-app-env]
  {:pre [(:needs-init? old-app-env) (:initializing? old-app-env)]}
  (merge old-app-env {:initializing? false
                      :needs-init? false}))

(defn reset-init-state
  [old-app-env]
  (merge old-app-env {:initializing? false
                      :needs-init? true}))

(defn init-repl-if-necessary!
  [opts data]
  (when (:needs-init? (swap! app-env update-to-initializing))
    (do (init-repl! opts data)
        (swap! app-env update-to-initialized))))

(defn force-init!
  "Force the initialization at the next read-eval-call. Use this every
  time an option that needs to be read at initialization time changes,
  e.g. :source-path. In the future this will be automated."
  []
  (swap! app-env reset-init-state))

;;;;;;;;;;;;;;;;;;;;;;
;;; Read-Eval-Call ;;;
;;;;;;;;;;;;;;;;;;;;;;

(defn read-eval-call
  "Reads, evaluates and calls back with the evaluation result.

  The first parameter is a map of configuration options, currently
  supporting:

  * :verbose - will enable the the evaluation logging, defaults to false
  * :warning-as-error - will consider a compiler warning as error
  * :target - :nodejs and :browser supported, the latter is used if
  missing
  * :init-fn! - user provided initialization function, it will be passed
  a map of data currently containing:

      :form   ;; the form to evaluate, as data, past the reader step
      :ns     ;; the current namespace, as symbol
      :target ;; *target* as keyword, :default is the default

  * :load-fn! - will override replumb's default cljs.js/*load-fn*.
  It rules out `:read-file-fn!`, losing any perk of using replumb.load
  helpers. Use it if you know what you are doing.

  * :read-file-fn!  an asynchronous 2-arity function (fn [file-path
  src-cb] ...) where src-cb is itself a function (fn [source] ...)  that
  needs to be called when ready with the found file source as
  string (nil if no file is found). It is mutually exclusive with
  :load-fn! and will be ignored in case both are present.

  * :src-paths - a vector of paths containing source files.

  The second parameter cb, is a 1-arity function which receives the
  result map.

  Therefore, given cb (fn [result-map] ...), the main map keys are:

  :success? ;; a boolean indicating if everything went right
  :value    ;; (if (success? result)) will contain the actual yield of the evaluation
  :error    ;; (if (not (success? result)) will contain a js/Error
  :warning  ;; in case a warning was thrown and :warning-as-error is falsey
  :form     ;; the evaluated form as data structure (not a string)

  The third parameter is the source string to be read and evaluated.

  It initializes the repl harness if necessary."
  [opts cb source]
  (try
    (let [expression-form (repl-read-string source)
          opts (normalize-opts opts) ;; AR - does the whole user option processing
          data {:form expression-form
                :ns (:current-ns @app-env)
                :target (keyword *target*)}]
      (init-repl-if-necessary! opts data)
      (when (:verbose opts)
        (common/debug-prn "Calling eval-str on" expression-form "with options" (common/filter-fn-keys opts)))
      (binding [ana/*cljs-warning-handlers* [(partial custom-warning-handler opts cb)]]
        (if (repl-special? expression-form)
          (process-repl-special opts cb data expression-form)
          (cljs/eval-str st
                         source
                         source
                         ;; opts (map)
                         (make-base-eval-opts! opts)
                         (fn [res]
                           (when (:verbose opts)
                             (common/debug-prn "Evaluation returned: " res))
                           (call-back! opts cb
                                       (merge data
                                              {:on-success-fn! #(do (process-1-2-3 data expression-form (:value res))
                                                                    (swap! app-env assoc :current-ns (:ns res)))})
                                       res))))))
    (catch :default e
      (when (:verbose opts)
        (common/debug-prn "Exception caught in read-eval-call: " (.-stack e)))
      (call-back! opts cb {} (common/wrap-error e)))))

(defn reset-env!
  "It does the following (in order):

  1. remove the input namespaces from the compiler environment
  2. set *e to nil
  3. reset the last warning
  4. in-ns to cljs.user

  It accepts a sequence of symbols or strings."
  ([]
   (reset-env! nil))
  ([namespaces]
   (doseq [ns namespaces]
     (purge-ns! st (symbol ns)))
   (if (seq @cljs.js/*loaded*)
     (throw (ex-info (str "The cljs.js/*loaded* atom still contains " @cljs.js/*loaded* " - make sure you purge dependent namespaces.") ex-info-data)))
   (assert (empty? @cljs.js/*loaded*))
   (reset-last-warning!)
   (read-eval-call {} identity "(set! *e nil)")
   (read-eval-call {} identity "(in-ns 'cljs.user)")))
