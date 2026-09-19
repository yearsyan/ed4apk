.class public Lcom/example/aqe/Helper;
.super Ljava/lang/Object;

.method public static onEnter()V
    .registers 2
    const-string v0, "AQE"
    const-string v1, "Helper called"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    return-void
.end method
