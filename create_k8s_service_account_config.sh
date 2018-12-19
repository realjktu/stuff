#!/bin/bash

if [ $# != 2 ]; then
    echo "Specify sa name and namespace"
    exit 2
fi
SA_NAME=$1
SA_NAMESPACE=$2
NEW_KUBECONFIG="k8s_cluster_${SA_NAME}.yaml"

kubectl -n ${SA_NAMESPACE} create sa ${SA_NAME}
# Create the yaml to bind the cluster admin role to user1
cat <<EOF >> /tmp/rbac-config-${SA_NAME}.yaml
apiVersion: rbac.authorization.k8s.io/v1beta1
kind: ClusterRoleBinding
metadata:
  name: ${SA_NAME}-binding
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: cluster-admin
subjects:
  - kind: ServiceAccount
    name: ${SA_NAME}
    namespace: ${SA_NAMESPACE}
EOF

# Apply the policy to user1
kubectl apply -f /tmp/rbac-config-${SA_NAME}.yaml

# Get related secret
secret=$(kubectl -n ${SA_NAMESPACE} get sa ${SA_NAME} -o json | jq -r .secrets[].name)
# Get ca.crt from secret 
kubectl -n ${SA_NAMESPACE} get secret $secret -o json | jq -r '.data["ca.crt"]' | base64 --decode > /tmp/${SA_NAME}_ca.crt
# Get service account token from secret
user_token=$(kubectl -n ${SA_NAMESPACE} get secret $secret -o json | jq -r '.data["token"]' | base64 --decode)
# Get information from your kubectl config (current-context, server..)
# get current context
c=`kubectl config current-context`
# get cluster name of context
name=`kubectl config get-contexts $c | awk '{print $3}' | tail -n 1`
# get endpoint of current context 
endpoint=`kubectl config view -o jsonpath="{.clusters[?(@.name == \"$name\")].cluster.server}"`



kubectl --kubeconfig=${NEW_KUBECONFIG} config set-cluster k8s-cluster --embed-certs=true --server=$endpoint --certificate-authority=/tmp/${SA_NAME}_ca.crt
kubectl --kubeconfig=${NEW_KUBECONFIG} config set-credentials ${SA_NAME}-k8s-cluster --token=$user_token
kubectl --kubeconfig=${NEW_KUBECONFIG} config set-context ${SA_NAME}-k8s-cluster --cluster=k8s-cluster --user=${SA_NAME}-k8s-cluster --namespace=default
kubectl --kubeconfig=${NEW_KUBECONFIG} config use-context ${SA_NAME}-k8s-cluster
