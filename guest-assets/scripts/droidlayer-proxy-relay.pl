#!/usr/bin/perl
use strict;
use warnings;
use IO::Socket::INET;
use IO::Select;
use POSIX qw(:errno_h);

$| = 1; # autoflush STDOUT

my @DEVICE_CANDIDATES = (
    "/dev/virtio-ports/droidlayer.proxy",
    "/dev/vport0p2",
    "/dev/droidlayer-proxy",
);

my $PORT = 8080;
my $CMD_DATA  = 1;
my $CMD_CLOSE = 2;
my $CMD_RESET = 3;

my $serial_path;
for my $path (@DEVICE_CANDIDATES) {
    if (-e $path) {
        $serial_path = $path;
        last;
    }
}

die "No virtio-serial proxy port found!\n" unless $serial_path;

open(my $serial_fh, "+<", $serial_path)
    or die "Cannot open $serial_path: $!\n";
binmode($serial_fh);

my $server = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => $PORT,
    Proto     => 'tcp',
    Listen    => 64,
    ReuseAddr => 1,
) or die "Cannot listen on 127.0.0.1:$PORT: $!\n";

print "[droidlayer-proxy-relay] Serving on 127.0.0.1:$PORT -> $serial_path\n";

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
