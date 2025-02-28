(ns district.server.smart-contracts
  (:require [cljs-web3-next.core :as web3-core]
            [cljs-web3-next.eth :as web3-eth]
            [cljs-web3-next.helpers :as web3-helpers]
            [cljs.core.async :refer [<! timeout] :as async]
            [cljs.core.async.impl.protocols]
            [cljs.nodejs :as nodejs]
            [clojure.set :as clojure-set]
            [clojure.string :as string]
            [district.server.config :refer [config]]
            [district.server.web3 :refer [web3]]
            [district.shared.async-helpers :as async-helpers]
            [district.shared.async-helpers :refer [promise->]]
            [mount.core :as mount :refer [defstate]]
            [taoensso.timbre :as log])
  (:require-macros [cljs.core.async.macros :refer [go-loop go]]))

(def fs (nodejs/require "fs"))
(def process (nodejs/require "process"))

(declare start)

(defstate smart-contracts :start (start (merge (:smart-contracts @config)
                                               (:smart-contracts (mount/args))))
  :stop ::stopped)

(defn contract [contract-key]
  (get @(:contracts @smart-contracts) contract-key))

(defn contract-address [contract-key]
  (:address (contract contract-key)))

(defn contract-name [contract-key]
  (:name (contract contract-key)))

(defn contract-abi [contract-key]
  (:abi (contract contract-key)))

(defn contract-bin [contract-key]
  (:bin (contract contract-key)))

(defn instance
  ([contract-key]
   (let [contr (contract contract-key)]
     (if-not (:forwards-to contr)
       (:instance contr)
       (instance (:forwards-to contr) contract-key))))
  ([contract-key contract-key-or-addr]
   (web3-eth/contract-at @web3 (contract-abi contract-key) (if (keyword? contract-key-or-addr)
                                                             (contract-address contract-key-or-addr)
                                                             contract-key-or-addr))))

(defn contract-by-address [contract-address]
  (reduce-kv (fn [_ contract-key {:keys [:address] :as contract}]
               (when (= (string/lower-case contract-address) (string/lower-case address))
                 (reduced (assoc contract :contract-key contract-key))))
             nil
             @(:contracts @smart-contracts)))

(defn update-contract! [contract-key contract]
  (swap! (:contracts @smart-contracts) update contract-key merge contract))

(defn- fetch-contract
  "Given a file-name and a path tries to load abi and bytecode.
  It first try to load it from a json truffle artifact, if it doesn't find it
  tries .abi .bin files for the name.
  Returns a map with :abi and :bin keys."
  [file-name & [{:keys [:path]}]]
  (let [path (or path (str (.cwd process) "/resources/public/contracts/build/"))
        json-file-path (str path file-name ".json")
        abi-file-path (str path file-name ".abi")
        bin-file-path (str path file-name ".bin")]
    (if (.existsSync fs json-file-path)

      (let [content-str (.readFileSync fs json-file-path "utf-8")
            json-file-content (js/JSON.parse content-str)]

        {:abi (aget json-file-content "abi")
         :bin (aget json-file-content "bytecode")})
      {:abi (when (.existsSync fs abi-file-path) (js/JSON.parse (.readFileSync fs abi-file-path "utf-8")))
       :bin (when (.existsSync fs bin-file-path) (.readFileSync fs bin-file-path "utf-8"))})))

(defn load-contract-files [contract {:keys [:contracts-build-path]}]
  (let [{:keys [abi bin]} (fetch-contract (:name contract) {:path contracts-build-path})]

    (when-not abi
      (println "Couldn't find ABI for " (:name contract)))

    (when-not bin
      (println "Couldn't find bin for " (:name contract)))

    (merge contract
           {:abi abi
            :bin bin
            :instance (web3-eth/contract-at @web3 abi (:address contract))})))

(defn instance-from-arg [contract & [{:keys [:ignore-forward?]}]]
  (cond
    (and ignore-forward? (keyword? contract)) (instance contract contract)
    (keyword? contract) (instance contract)
    (sequential? contract) (instance (first contract) (second contract))
    :else contract))

(defn- enrich-event-log [contract-name contract-instance {:keys [:event :return-values] :as log}]
  (-> log
      (update :return-values #(web3-helpers/return-values->clj return-values (web3-helpers/event-interface contract-instance event)))
      (update :event (fn [event-name]
                       (if (= (first event-name)
                              (string/upper-case (first event-name)))
                         (keyword event-name)
                         (web3-helpers/kebab-case (keyword event-name)))))
      (assoc :contract (dissoc (contract-by-address (:address log))
                               :abi :bin :instance))
      (clojure-set/rename-keys {:return-values :args})))

(defn contract-call
  "Will call a method and execute its smart contract method in the EVM without sending any transaction.
   # arguments:
   ## `contract` parameter can be one of:
   * keyword :some-contract
   * tuple of keyword and address [:some-contract 0x1234...]
   * instance SomeContract
   ## `method` is a :camel_case keyword corresponding to the smart-contract function
   ## `args` is a vector of arguments for the `method`
   ## `opts` is a map of options passed as message data
   # returns:
   function returns a Promise resolving to the result of `method` call."
  ([contract method args {:keys [:ignore-forward?] :as opts}]
   (web3-eth/contract-call (instance-from-arg contract {:ignore-forward? ignore-forward?})
                           method
                           args
                           (dissoc opts :ignore-forward?)))
  ([contract method args]
   (contract-call contract method args {}))
  ([contract method]
   (contract-call contract method [] {})))

(defn contract-send
  "Will send a transaction to the smart contract and execute its method.
   # arguments:
   ## `contract` parameter can be one of:
   * keyword :some-contract
   * tuple of keyword and address [:some-contract 0x1234...]
   * instance SomeContract
   ## `method` is a :camel_case keyword corresponding to the smart-contract function
   ## `args` is a vector of arguments for the `method`
   ## `opts` is a map of options passed as message data
   # Returns:
     default: returns a Promise resolving to a tx receipt

     This can be configured with :output keyword of `opts` values:
       :receipt-or-nil - will return Tx receipt on success, nil in case of revert
       :receipt-error-pair - returns vector consisting of [tx-receipt error-object]
       :receipt-or-error - returns single object, Tx receipt in case of success, error-object (js/Error) in case of revert
  "
  ([contract method args {:keys [:from :gas :ignore-forward?] :as opts}]
   (go
     (let [accounts (<! (web3-eth/accounts @web3))
           account (or from (first accounts))
           opts (merge {:from (first accounts)} (when-not gas {:gas 4000000}) (dissoc opts :ignore-forward?))
           output-format (get opts :output :receipt-or-nil)
           opts (dissoc opts :output)
           result (<! (web3-eth/contract-send (instance-from-arg contract {:ignore-forward? ignore-forward?}) method args opts))
           error? (= js/Error (type result))
           tx-receipt (if (not error?) (web3-helpers/js->cljkk result))
           error-object (if error? result)]
       (case output-format
         :receipt-or-nil (or tx-receipt nil)
         :receipt-error-pair [tx-receipt error-object]
         :receipt-or-error (or tx-receipt error-object)))))
  ([contract method args]
   (contract-send contract method args {}))
  ([contract method]
   (contract-send contract method [] {})))

(defn subscribe-events [contract event {:keys [:from-block :address :topics :ignore-forward? :latest-event?] :as opts} callbacks]
  (let [contract-instance (instance-from-arg contract {:ignore-forward? ignore-forward?})]
    (web3-eth/subscribe-events contract-instance
                               event
                               opts
                               (fn [error evt]
                                 (if callbacks
                                   ;; if we have callbacks registered, fire this event in all of them
                                   (let [enriched-evt (->> evt
                                                           web3-helpers/js->cljkk
                                                           (#(assoc % :latest-event? latest-event?))
                                                           (enrich-event-log contract contract-instance))]
                                     (doseq [callback callbacks]
                                       (callback error enriched-evt)))

                                   (log/warn (str "No callback specified for event " evt)))))))

(defn subscribe-event-logs [contract event {:keys [:from-block :address :topics :ignore-forward?] :as opts} & [callback]]
  (let [contract-instance (instance-from-arg contract {:ignore-forward? ignore-forward?})
        event-signature (:signature (web3-helpers/event-interface contract-instance event))]
    (web3-eth/subscribe-logs @web3
                             (merge {:address (aget contract-instance "options" "address")
                                     :topics [event-signature]}
                                    opts)
                             (fn [error event]
                               (callback error (web3-helpers/js->cljkk event))))))
(defn- get-events-from-tx
  "Returns a promise that will resolve to the list of requested event data, decoded"
  [contract event {:keys [:transaction-hash] :as tx-receipt} getter-fn]
  (go
    (let [contract-instance (instance-from-arg contract)
          {:keys [:signature] :as event-interface} (web3-helpers/event-interface contract-instance event)
          new-tx-receipt (<! (web3-eth/get-transaction-receipt @web3 transaction-hash))
          {:keys [:logs :inputs]} (web3-helpers/js->cljkk new-tx-receipt)
          sought-event? (fn [{:keys [:topics]}] (= signature (first topics)))
          decode-event-data (fn [{:keys [:data :topics]}] (web3-eth/decode-log @web3 (:inputs event-interface) data (drop 1 topics)))
          clojurize (fn [return-values] (web3-helpers/return-values->clj return-values event-interface))]
      (->> logs
           (filter sought-event? ,,,)
           (map decode-event-data ,,,)
           (map clojurize ,,,)
           getter-fn ,,,))))

(defn contract-events-in-tx [contract event tx-receipt]
  (get-events-from-tx contract event tx-receipt identity))

(defn contract-event-in-tx [contract event tx-receipt]
  (get-events-from-tx contract event tx-receipt first))

(defn wait-for-block
  "Blocks until block with block-number arrives.
   callback is a nodejs style callback i.e. (fn [error data] ...)"
  [block-number callback]
  (web3-eth/get-block @web3 block-number (fn [error response]
                                           (if error
                                             (callback error nil)
                                             (if response
                                               (callback nil response)
                                               (js/setTimeout #(wait-for-block block-number callback) 1000))))))

(defn replay-past-events-in-order
  "Replay all past events in order.
  :from-block specifies the first block number events should be dispatched.
  :skip-log-indexes, a set of tuples like [tx log-index] for the :from-block block that should be skipped."
  [events callback {:keys [:from-block :skip-log-indexes :to-block :block-step
                           :ignore-forward? :crash-on-event-fail?
                           :transform-fn :on-finish]
                    :or {transform-fn identity}
                    :as opts}]

  (when (and skip-log-indexes (not from-block ))
    (throw (js/Error. "replay-past-events-in-order: Can't specify skip-log-indexes without specifying :from-block")))

  (let [log-order-triplet (juxt :block-number :transaction-index :log-index)
        from-blocks (range from-block to-block block-step)
        last-from (last from-blocks)
        from-blocks (concat from-blocks [(+ block-step last-from)])
        logs-chans (for [[k [contract event]] events
                         from from-blocks]
                     (let [logs-ch (async/promise-chan)
                           contract-instance (instance-from-arg contract {:ignore-forward? ignore-forward?})
                           to (min to-block (+ from (dec block-step)))]

                       (log/debug "Processing chunk of blocks" {:contract contract
                                                                :event event
                                                                :from from
                                                                :to to})

                       (web3-eth/get-past-events contract-instance
                                                 event
                                                 {:from-block from
                                                  :to-block to}
                                                 (fn [error events]
                                                   (let [logs (->> events
                                                                   web3-helpers/js->cljkk
                                                                   (map (partial enrich-event-log contract contract-instance)))]
                                                     (async/put! logs-ch {:err error :logs logs}))))
                       logs-ch))]

    ;; go chan by chan collecting events
    (go-loop [all-logs []
              [logs-ch & rest-logs] logs-chans]
      (if logs-ch
        (let [{:keys [:err :logs]} (async/<! logs-ch)
              logs (map #(assoc % :err err) logs)]
          ;; keep collecting
          (recur (into all-logs logs) rest-logs))

        ;; no more channels to read, sort and callback
        (let [sorted-logs (cond->> (sort-by log-order-triplet all-logs)

                            skip-log-indexes (remove (fn [l]
                                                       (and (= (:block-number l) from-block)
                                                            (skip-log-indexes [(:transaction-index l) (:log-index l)]))))
                            true             transform-fn)]
          (go-loop [logs sorted-logs]
            (if (seq logs)
              (do
                (let [first-log (first logs)]

                  (when (fn? callback)
                    (doseq [res (try
                                  (callback (:err first-log) (dissoc first-log :err))
                                  (catch js/Error e
                                    (when crash-on-event-fail?
                                      (log/error "Server crash. Caused by event processing error with :crash-on-event-fail? true. Disable this flag to skip and continue.")
                                      (.exit js/process 1))))]
                      ;; if callback returns a promise or chan we block until it resolves
                      (cond
                        (satisfies? cljs.core.async.impl.protocols/ReadPort res)
                        (<! res)

                        (async-helpers/promise? res)
                        (<! (async-helpers/promise->chan res))))))
                (recur (rest logs)))

              (when (fn? on-finish)
                (on-finish sorted-logs)))))))))

(defn start [{:keys [:contracts-var] :as opts}]
  (merge
   {:contracts (atom (into {} (map (fn [[k v]]
                                     [k (load-contract-files v opts)])
                                   @contracts-var)))}
   opts))
