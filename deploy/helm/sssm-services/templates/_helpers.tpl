{{- define "sssm-services.labels" -}}
app.kubernetes.io/part-of: sssm
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "sssm-services.image" -}}
{{- $top := index . 0 -}}
{{- $name := index . 1 -}}
{{- $svc := index . 2 -}}
{{- printf "%s/%s:%s" $top.Values.global.imageRegistry $name ($svc.tag | default $top.Values.global.imageTag) -}}
{{- end -}}
