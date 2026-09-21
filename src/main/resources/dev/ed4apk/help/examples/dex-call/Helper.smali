.class public Lcom/example/ed4apk/Helper;
.super Ljava/lang/Object;

.method public static onEnter()V
    .registers 2
    const-string v0, "ed4apk"
    const-string v1, "Helper called"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method
