(ns rangle.core
  (:require
    [cljs.core.async :as async :refer [<! >! chan put! timeout]]
    [jayq.core :as jq :refer [$]]
    [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(defn log-return-value [message f & args]
  (let [ret (apply f args)]
    (println message ret)
    ret))

(def width 800)
(def height 600)

(def path-width 5)
(def new-point-threshold 5)
(def close-tolerance 15)

(def closed-path-fadeout-millis 500)
(def invalid-path-fadeout-millis 250)

(def dot-radius 5)
(def dot-border-width 3)
(def dot-fadeout-millis 500)
(def dot-fadeout-radius (* 2 dot-radius))

(def min-dot-distance 25)
(def min-dot-edge-distance min-dot-distance)
(def num-dot-candidates 2)
(def max-dot-attempts 100)

(def dot-colors ["red" "green" "blue" "purple"])
(def num-colors (count dot-colors))

(def num-initial-dots 50)

(defn square [x] (* x x))

(def sq-new-point-threshold (square new-point-threshold))
(def sq-close-tolerance (square close-tolerance))
(def sq-min-dot-distance (square min-dot-distance))

(defn xy-message [ch message-name e]
  (put! ch [message-name [(.-offsetX e) (.-offsetY e)]]))

(defn mousemove-handler [ch e]
  (if (pos? (.-which e))
    (xy-message ch :draw e)
    (put! ch [:drawend])))

(defn draw-event-capture [ch selector]
  (jq/on ($ selector) :mousemove (partial mousemove-handler ch))
  (jq/on ($ selector) :mousedown (partial xy-message ch :drawstart))
  (jq/on ($ selector) [:mouseup :mouseleave] (fn [] (put! ch [:drawend]))))

(defn vec-command [command & points]
  (str command (str/join " " (flatten points))))

(defn smooth-path-from-points [points closed?]
  (cond
    (empty? points) ""
    (= (count points) 1) (vec-command "M" (first points))
    :else (let [midpoint (fn [[x1 y1] [x2 y2]] [(/ (+ x1 x2) 2) (/ (+ y1 y2) 2)])
                inner-points (map midpoint (subvec points 1) (subvec points 2 (dec (count points))))
                inner-curves (if (> (count points) 3)
                               (map-indexed (fn [i midpoint] (vec-command "Q" (points (inc i)) midpoint)) inner-points)
                               "")
                first-curve (vec-command "M" (first points))
                last-curve (vec-command "Q" (points (- (count points) 2)) (last points))
                closing (if closed? "Z" "")]
            (str first-curve (str/join inner-curves) last-curve closing))))

(defn simple-path-from-points [points]
  (let [splat (fn [code point] (str code (point 0) " " (point 1)))
        [start & others] points]
    (apply str (cons (splat "M" start) (map (partial splat "L") points)))))

(defn distance-squared [[x1 y1] [x2 y2]]
  (let [square (fn [x] (* x x))]
    (+ (square (- x1 x2)) (square (- y1 y2)))))

(defn async-some [predicate ch]
  (go
    (loop []
      (let [message (<! ch)]
        (if (predicate message)
          message
          (recur))))))

(defn path-string [path]
  (.toString (.attr path "path")))

(defn path-is-closed? [path]
  (let [last-char (last (path-string path))]
    (or (= last-char \z) (= last-char \Z))))

(defn next-message [message-name-set ch]
  (async-some (comp message-name-set first) ch))

(defn update-path [path points closed?]
  (.attr path "path" (smooth-path-from-points points closed?)))

(defn should-close? [points]
  (and (some #(> (distance-squared (first points) %) sq-close-tolerance) points)
       (< (distance-squared (first points) (last points)) sq-close-tolerance)))

(defn animate-loop-exit [path]
  (.attr path (js-obj "fill" "blue"
                      "fill-opacity" 0.5))
  (.animate path
            (js-obj "opacity" 0
                    "fill-opacity" 0)
            closed-path-fadeout-millis
            "<>"
            (fn [] (.remove path))))

(defn animate-invalid-exit [path]
  (.animate path (js-obj "opacity" 0
                         "stroke" "red") invalid-path-fadeout-millis "<>"))

(defn sq-closest-distance
  "Returns the square of the distance to the closest dot."
  [location other-locations]
  (apply min (map (partial distance-squared location) other-locations)))

(defn random-dot-location-no-candidates
  "Generates a random dot location by randomly choosing locations and rejecting
  those which are too close to other dots. May return nil in degenerate cases."
  [current-locations]
  (loop [attempts-left max-dot-attempts]
    (if (zero? attempts-left)
      nil
      (let [rand-in-middle (fn [whole]
                             (+ min-dot-edge-distance
                                (rand-int (- whole (* 2 min-dot-edge-distance)))))
            x (rand-in-middle width)
            y (rand-in-middle height)
            loc [x y]]
        (if (or (empty? current-locations)
                (> (sq-closest-distance loc current-locations) sq-min-dot-distance))
          loc
          (recur (dec attempts-left)))))))

(defn random-dot-location
  "Generates a random dot location which attempts to be not too close to any
  other dots. This may return nil in degenerate scenarios."
  [current-locations]
  (if (empty? current-locations)
    (random-dot-location-no-candidates current-locations)
    (let [candidates (filter (comp not nil?)
                             (repeatedly num-dot-candidates
                                         #(random-dot-location-no-candidates current-locations)))]
      (if (empty? candidates)
        nil
        (apply max-key #(sq-closest-distance % current-locations) candidates)))))

(defn create-dot-element [paper color [x y]]
  (let [dot-element (.circle paper x y dot-radius)]
    (.attr dot-element (js-obj "fill" color
                               "stroke" "gray"
                               "stroke-width" dot-border-width))
    dot-element))

(defn create-dot [paper color loc]
  {:element (create-dot-element paper color loc)
   :loc loc
   :color color})

(defn random-color []
  (dot-colors (rand-int num-colors)))

(defn dot-locations [dots]
  (map :loc dots))

(defn create-random-dot [paper current-dots]
  (create-dot paper (random-color) (random-dot-location (dot-locations current-dots))))

(defn create-random-dots [n paper current-dots]
  (loop [i n
         dots current-dots
         new-dots []]
    (if (zero? i)
      new-dots
      (let [new-dot (create-random-dot paper dots)]
        (recur (dec i) (conj dots new-dot) (conj new-dots new-dot))))))

(defn animate-dot-exit [dot]
  (let [dot-element (:element dot)]
    (.animate dot-element (js-obj "opacity" 0) dot-fadeout-millis "<>")
    (.animate dot-element
              (js-obj "r" dot-fadeout-radius)
              (/ dot-fadeout-millis 2)
              "<"
              (fn []
                (.animate dot-element
                          (js-obj "r" 0)
                          (/ dot-fadeout-millis 2)
                          ">"
                          (fn [] (.remove dot-element)))))))

(defn enclosed-dots [path dots]
  (filter (fn [dot]
            (let [[x y] (:loc dot)]
              (.isPointInside path x y)))
          dots))

(defn test-dots [paper n]
  (loop [i n
         locs []]
    (if (pos? i)
      (let [loc (random-dot-location locs)]
        (if loc
          (let [dot (create-dot paper "green" loc)]
            (animate-dot-exit dot)))
        (let [new-locs (if loc (conj locs loc) locs)]
          (recur (dec i) new-locs))))))

(defn ^:export inhabit [selector]
  (let
    [$container
     ($ selector)

     paper
     (js/Raphael (.get $container 0) width height)

     draw-ch
     (let [ch (chan)]
       (draw-event-capture ch $container)
       ch)

     new-path
     (fn [initial-point]
       (let [curve (.path paper (vec-command "M" initial-point))]
         (.attr curve (js-obj "stroke" "black"
                              "stroke-width" path-width
                              "stroke-linecap" "round"
                              "stroke-linejoin" "round"))
         curve))

     draw-loop
     (fn [initial-point]
       (let [curve (new-path initial-point)]
         (go 
           (loop [points [initial-point]]
             (update-path curve points false)
             (let [[message-name data] (<! draw-ch)]
               (if (= message-name :draw)
                 (let [new-points (if (> (distance-squared (last points) data) sq-new-point-threshold)
                                    (conj points data)
                                    points)]
                   (if (should-close? points)
                     (do (update-path curve points true)
                         curve)
                     (recur new-points)))
                 curve))))))


     add-background 
     (fn []
       (let [rect (.rect paper 0 0 width height)]
         (.attr rect (js-obj "fill" "270-#fff-#808080"
                             "stroke" 0))))

     disable-drag-selection
     (fn []
       (jq/on $container :mousedown jq/prevent))

     initialize
     (fn []
       (add-background)
       (disable-drag-selection)
       (jq/css $container :display "inline-block")
       (go
         (loop [curve nil
                dots (create-random-dots num-initial-dots paper [])]
           (let [[_ point] (<! (next-message #{:drawstart} draw-ch))]
             (if curve (.remove curve))
             (let [curve (<! (draw-loop point))]
               (if (path-is-closed? curve)
                 (do
                   (animate-loop-exit curve)
                   (let [dots-to-remove (set (enclosed-dots curve dots))]
                     (doseq [dot dots-to-remove] (animate-dot-exit dot))
                     (recur curve (filter #(not (contains? dots-to-remove %)) dots))))
                 (do
                   (animate-invalid-exit curve)
                   (recur curve dots))))))))
     ]
    (initialize)))
