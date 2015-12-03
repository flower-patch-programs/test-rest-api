(ns rest-api.handler
  (:require [environ.core :refer [env]]
            [cheshire.core :refer :all]
            [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [langohr.consumers :as lcons]
            [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]))

(def connection-config {:host (env :rabbitmq-host)
                        :username (env :rabbitmq-username)
                        :password (env :rabbitmq-password)
                        :port (Integer/parseInt (or (env :rabbitmq-port) "0"))
                        :vhost (env :rabbitmq-vhost)})

;; (def connection-config {:host "127.0.0.1"
;;                         :username "guest"
;;                         :password "guest"
;;                         :port 5672
;;                         :vhost "/"})

(def ^{:const true} q "flowerpatch.calculator")

(defn correlation-id-equals?
  [correlation-id d]
  (= (.getCorrelationId (.getProperties d)) correlation-id))


(defrecord CalculatorRPCClient [conn ch cbq consumer]
  clojure.lang.IFn
  (invoke [this n]
    (let [correlation-id (str (java.util.UUID/randomUUID))]
      (lb/publish ch "" q (str n) {:reply-to cbq
                                   :correlation-id correlation-id})
      (lb/consume ch cbq consumer)
      (-> (first (filter (partial correlation-id-equals? correlation-id)
                         (lcons/deliveries-seq consumer)))
          .getBody
          (String. "UTF-8")
          (read-string))))
  java.io.Closeable
  (close [this]
    (.close conn)))

(defn make-calculator-rpc-client
  []
  (let [conn     (rmq/connect connection-config)
        ch       (lch/open conn)
        cbq      (lq/declare ch "" {:auto-delete false :exclusive true})
        consumer (lcons/create-queueing ch {})]
    (->CalculatorRPCClient conn ch (:queue cbq) consumer)))

(defn ask-and-wait [operator numbers]
  (with-open [calculator-rpc (make-calculator-rpc-client)]
    (println "sending message" operator numbers)
    (calculator-rpc (generate-string {:operator operator
                                      :numbers numbers}))))

(defroutes app-routes
  (GET "/" [] "Hello World")
  (GET "/plus" {params :params} (do (println "params" params) (str (ask-and-wait "+" (map read-string (:n params)))))) ;;warning read-string
  (GET "/:operator/" [operator n] (println operator n))

  (route/not-found "Not Found"))

(def app
  (-> app-routes
      handler/api))

(defn -main
  [& args]
  ;;(println connection-config)
  ;;(run-jetty app-routes {:port 8080}) ;; start the webserver
)
