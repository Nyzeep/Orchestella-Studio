#!/bin/sh
# Shell preamble: if perl is not installed on this guest (e.g. Alpine minimal), exit cleanly without crashing
if ! command -v perl >/dev/null 2>&1; then
    exit 0
fi
exec perl -x "$0" "$@"
#!perl
use strict;
use warnings;
use IO::Socket::INET;
use IO::Select;
use POSIX qw(:errno_h);

$| = 1; # autoflush STDOUT

my @CANDIDATE_PATHS = (
    "/dev/virtio-ports/orchestella.proxy",
    "/dev/orchestella-proxy",
    "/dev/vport0p2",
);

my $PORT = 8080;
my $CMD_DATA  = 1;
my $CMD_CLOSE = 2;
my $CMD_RESET = 3;

my $serial_fh;
my $serial_path;

for my $retry (1..60) {
    my @paths = @CANDIDATE_PATHS;
    for my $dev (glob("/dev/virtio-ports/*")) {
        push @paths, $dev unless $dev =~ /bridge/;
    }
    for my $path (@paths) {
        if (-e $path) {
            if (open(my $fh, "+<", $path)) {
                $serial_fh = $fh;
                $serial_path = $path;
                last;
            }
        }
    }
    last if $serial_fh;
    select(undef, undef, undef, 0.5); # sleep 500ms
}

die "[orchestella-proxy-relay] No virtio-serial proxy port found or cannot open after retries: $!\n" unless $serial_fh;
binmode($serial_fh);

my $server = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => $PORT,
    Proto     => 'tcp',
    Listen    => 64,
    ReuseAddr => 1,
) or die "Cannot listen on 127.0.0.1:$PORT: $!\n";

print "[orchestella-proxy-relay] Serving on 127.0.0.1:$PORT -> $serial_path\n";

my $sel = IO::Select->new();
$sel->add($server);
$sel->add($serial_fh);

my %client_to_id;
my %id_to_client;
my $next_cid = 1;
my $serial_buf = "";

while (1) {
    my @ready = $sel->can_read(1.0);
    for my $fh (@ready) {
        if ($fh == $server) {
            my $client = $server->accept();
            next unless $client;
            $client->blocking(0);
            my $cid = $next_cid++;
            $next_cid = 1 if $next_cid > 0x7FFFFFFF;
            $client_to_id{$client} = $cid;
            $id_to_client{$cid} = $client;
            $sel->add($client);
        } elsif ($fh == $serial_fh) {
            my $raw;
            my $n = sysread($serial_fh, $raw, 16384);
            if (!defined($n) || $n == 0) {
                # Virtio serial closed or error
                next;
            }
            $serial_buf .= $raw;

            while (length($serial_buf) >= 9) {
                my ($cid, $cmd, $len) = unpack("N C N", substr($serial_buf, 0, 9));
                if (length($serial_buf) < 9 + $len) {
                    last; # wait for rest of payload
                }
                my $payload = substr($serial_buf, 9, $len);
                substr($serial_buf, 0, 9 + $len, ""); # consume frame

                if ($cmd == $CMD_DATA) {
                    my $client = $id_to_client{$cid};
                    if ($client) {
                        syswrite($client, $payload);
                    }
                } elsif ($cmd == $CMD_CLOSE || $cmd == $CMD_RESET) {
                    my $client = $id_to_client{$cid};
                    if ($client) {
                        $sel->remove($client);
                        delete $client_to_id{$client};
                        delete $id_to_client{$cid};
                        close($client);
                    }
                }
            }
        } else {
            # Client socket readable
            my $client = $fh;
            my $cid = $client_to_id{$client};
            unless (defined $cid) {
                $sel->remove($client);
                close($client);
                next;
            }

            my $buf;
            my $n = sysread($client, $buf, 16384);
            if (defined($n) && $n > 0) {
                my $frame = pack("N C N", $cid, $CMD_DATA, $n) . $buf;
                syswrite($serial_fh, $frame);
            } else {
                # EOF or error
                my $frame = pack("N C N", $cid, $CMD_CLOSE, 0);
                syswrite($serial_fh, $frame);
                $sel->remove($client);
                delete $client_to_id{$client};
                delete $id_to_client{$cid};
                close($client);
            }
        }
    }
}
