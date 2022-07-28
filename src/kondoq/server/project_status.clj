(ns kondoq.server.project-status
  "Manage temporary project status properties, while a project is being added.
  In memory, works because of single writer.")

(def current-projects (atom {}))

(defn init-project-status [location project-future]
  (swap! current-projects (fn [m]
                            (assoc m location {:future project-future
                                               :location location
                                               :project ""
                                               :ns-count 0
                                               :ns-total -1}))))

(defn update-project-status [location project ns-count ns-total current-file]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :project project
                                                :ns-count ns-count
                                                :ns-total ns-total
                                                :current-file current-file}))))))

(defn update-project-with-error [location error]
  (swap! current-projects (fn [m]
                            (update m location
                                    (fn [p]
                                      (merge p {:location location
                                                :error error}))))))

(defn fetch-project-status [location]
  (get @current-projects location))

(defn delete-project-status [location]
  (swap! current-projects (fn [m] (dissoc m location))))

